package org.readera.openreadera.ui.pdf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.readera.openreadera.data.pdf.PdfSignatureAlignment
import org.readera.openreadera.data.pdf.PdfSignaturePoint
import org.readera.openreadera.data.pdf.PdfSignatureStroke
import org.readera.openreadera.data.pdf.PdfVisualSignature
import kotlin.math.abs
import kotlin.math.hypot

private val SIGNATURE_ALIGNMENTS = listOf(
    PdfSignatureAlignment.LEFT to "Izquierda",
    PdfSignatureAlignment.CENTER to "Centro",
    PdfSignatureAlignment.RIGHT to "Derecha"
)

private const val MAX_SIGNATURE_STROKES = 100
private const val MAX_SIGNATURE_POINTS = 10_000
private const val MIN_POINT_DELTA = 0.0025f
private const val MIN_CANVAS_ASPECT_RATIO = 1.2f
private const val MAX_CANVAS_ASPECT_RATIO = 6.0f
private const val ASPECT_RATIO_EPSILON = 0.05f

@Composable
fun PdfSignatureDialog(
    pageCount: Int,
    initialPage: Int,
    isSaving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (PdfVisualSignature) -> Unit
) {
    val safePageCount = pageCount.coerceAtLeast(0)
    val initialPageNumber = if (safePageCount > 0) initialPage.coerceIn(0, safePageCount - 1) + 1 else 1
    var pageText by remember(safePageCount, initialPage) {
        mutableStateOf(if (safePageCount > 0) initialPageNumber.toString() else "")
    }
    var alignment by remember { mutableStateOf(PdfSignatureAlignment.RIGHT) }
    var consentChecked by remember { mutableStateOf(false) }
    val strokes = remember { mutableStateListOf<PdfSignatureStroke>() }
    val activePoints = remember { mutableStateListOf<PdfSignaturePoint>() }
    var committedPoints by remember { mutableIntStateOf(0) }
    var canvasAspectRatio by remember { mutableFloatStateOf(0f) }

    val parsedPage = pageText.trim().toIntOrNull()
    val validPage = parsedPage?.takeIf { safePageCount > 0 && it in 1..safePageCount }
    val pageError = when {
        safePageCount <= 0 -> "El documento no tiene páginas válidas."
        pageText.isBlank() || parsedPage == null || parsedPage !in 1..safePageCount ->
            "Introduce una página entre 1 y $safePageCount."
        else -> null
    }

    val totalPoints = committedPoints + activePoints.size
    val hasValidAspectRatio = canvasAspectRatio.isFinite() &&
        canvasAspectRatio in MIN_CANVAS_ASPECT_RATIO..MAX_CANVAS_ASPECT_RATIO
    val hasValidSignature = strokes.isNotEmpty() &&
        strokes.size <= MAX_SIGNATURE_STROKES &&
        committedPoints in 2..MAX_SIGNATURE_POINTS &&
        totalPoints <= MAX_SIGNATURE_POINTS &&
        hasValidAspectRatio
    val canSave = !isSaving && validPage != null && hasValidSignature && consentChecked

    fun clearStrokes() {
        strokes.clear()
        activePoints.clear()
        committedPoints = 0
    }

    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrBlank()) {
            clearStrokes()
            consentChecked = false
        }
    }

    fun handleDismiss() {
        if (!isSaving) {
            clearStrokes()
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = ::handleDismiss,
        title = {
            Text("Firma visual en PDF", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Es una marca visual en una copia. No verifica tu identidad ni usa un certificado digital; su validez legal depende del documento y la normativa aplicable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isSaving) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Guardando copia firmada…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Área de firma", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    OutlinedButton(
                        onClick = ::clearStrokes,
                        enabled = !isSaving && (strokes.isNotEmpty() || activePoints.isNotEmpty()),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Limpiar") }
                }

                val inkColor = MaterialTheme.colorScheme.onSurface
                val guideColor = MaterialTheme.colorScheme.outlineVariant
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(156.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .onSizeChanged { size ->
                            if (size.width > 0 && size.height > 0) {
                                val rawRatio = size.width.toFloat() / size.height.toFloat()
                                if (rawRatio.isFinite()) {
                                    val nextRatio = rawRatio.coerceIn(MIN_CANVAS_ASPECT_RATIO, MAX_CANVAS_ASPECT_RATIO)
                                    if (canvasAspectRatio > 0f && abs(nextRatio - canvasAspectRatio) > ASPECT_RATIO_EPSILON) {
                                        clearStrokes()
                                        consentChecked = false
                                    }
                                    canvasAspectRatio = nextRatio
                                }
                            }
                        }
                        .semantics { contentDescription = "Área de dibujo para firma manuscrita visual" }
                        .pointerInput(isSaving, safePageCount) {
                            if (isSaving || safePageCount <= 0) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val first = normalizePoint(down.position, w, h) ?: return@awaitEachGesture
                                down.consume()
                                if (strokes.size >= MAX_SIGNATURE_STROKES ||
                                    committedPoints + activePoints.size >= MAX_SIGNATURE_POINTS
                                ) return@awaitEachGesture
                                activePoints.clear()
                                activePoints.add(first)
                                var completed = false
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                            ?: event.changes.firstOrNull() ?: break
                                        val pt = normalizePoint(change.position, w, h)
                                        if (change.changedToUpIgnoreConsumed() || !change.pressed) {
                                            if (pt != null) appendPoint(activePoints, committedPoints, pt)
                                            change.consume()
                                            completed = true
                                            break
                                        }
                                        if (pt != null) appendPoint(activePoints, committedPoints, pt)
                                        change.consume()
                                    }
                                } finally {
                                    if (completed && activePoints.size >= 2 &&
                                        strokes.size < MAX_SIGNATURE_STROKES &&
                                        committedPoints + activePoints.size <= MAX_SIGNATURE_POINTS
                                    ) {
                                        val strokePoints = activePoints.toList()
                                        strokes.add(PdfSignatureStroke(strokePoints))
                                        committedPoints += strokePoints.size
                                    }
                                    activePoints.clear()
                                }
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val baselineY = size.height * 0.78f
                        drawLine(
                            color = guideColor,
                            start = Offset(16.dp.toPx(), baselineY),
                            end = Offset(size.width - 16.dp.toPx(), baselineY),
                            strokeWidth = 1.dp.toPx()
                        )
                        val widthPx = 2.5.dp.toPx()
                        strokes.forEach { drawSignatureStroke(it.points, inkColor, widthPx) }
                        if (activePoints.isNotEmpty()) drawSignatureStroke(activePoints, inkColor, widthPx)
                    }
                    if (strokes.isEmpty() && activePoints.isEmpty()) {
                        Text(
                            text = "Dibuja tu firma aquí",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                OutlinedTextField(
                    value = pageText,
                    onValueChange = { pageText = it.filter(Char::isDigit).take(6) },
                    enabled = !isSaving && safePageCount > 0,
                    singleLine = true,
                    label = { Text("Página (1 a $safePageCount)") },
                    isError = pageError != null,
                    supportingText = { if (pageError != null) Text(pageError) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Alineación inferior", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SIGNATURE_ALIGNMENTS.forEach { (option, label) ->
                        FilterChip(
                            selected = alignment == option,
                            onClick = { alignment = option },
                            enabled = !isSaving,
                            label = { Text(label) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                        value = consentChecked,
                        enabled = !isSaving,
                        role = Role.Checkbox,
                        onValueChange = { consentChecked = it }
                    ).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = consentChecked, onCheckedChange = null, enabled = !isSaving)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Entiendo que la copia con firma visual se añadirá a la biblioteca y podría subirse a Google Drive si la sincronización automática está activada.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val page = validPage ?: return@Button
                    if (canSave) {
                        onSave(
                            PdfVisualSignature(
                                pageIndex = page - 1,
                                strokes = strokes.toList(),
                                alignment = alignment,
                                canvasAspectRatio = canvasAspectRatio
                            )
                        )
                    }
                },
                enabled = canSave,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Guardar copia") }
        },
        dismissButton = {
            TextButton(onClick = ::handleDismiss, enabled = !isSaving, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancelar")
            }
        }
    )
}

private fun normalizePoint(offset: Offset, width: Float, height: Float): PdfSignaturePoint? {
    if (!offset.x.isFinite() || !offset.y.isFinite() || width <= 0f || height <= 0f) return null
    val nx = (offset.x / width).coerceIn(0f, 1f)
    val ny = (offset.y / height).coerceIn(0f, 1f)
    return if (nx.isFinite() && ny.isFinite()) PdfSignaturePoint(nx, ny) else null
}

private fun appendPoint(
    activePoints: MutableList<PdfSignaturePoint>,
    committedPoints: Int,
    point: PdfSignaturePoint
) {
    if (committedPoints + activePoints.size >= MAX_SIGNATURE_POINTS) return
    val last = activePoints.lastOrNull()
    if (last == null || hypot(point.x - last.x, point.y - last.y) >= MIN_POINT_DELTA) activePoints.add(point)
}

private fun DrawScope.drawSignatureStroke(points: List<PdfSignaturePoint>, color: Color, strokeWidthPx: Float) {
    if (points.isEmpty() || size.width <= 0f || size.height <= 0f) return
    if (points.size == 1) {
        drawCircle(color, strokeWidthPx / 2f, Offset(points[0].x * size.width, points[0].y * size.height))
        return
    }
    val path = Path().apply {
        moveTo(points[0].x * size.width, points[0].y * size.height)
        for (i in 1 until points.size) lineTo(points[i].x * size.width, points[i].y * size.height)
    }
    drawPath(path, color, style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
