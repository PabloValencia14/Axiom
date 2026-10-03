package org.readera.openreadera.ui.reader.components

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

data class PresentationStroke(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float
)

internal const val LASER_TRAIL_DURATION_MILLIS = 5_000L

internal data class LaserTrail(
    val points: List<Offset>,
    val expiresAtMillis: Long
)

internal fun addLaserTrail(trails: MutableList<LaserTrail>, points: List<Offset>, releasedAtMillis: Long) {
    trails.add(LaserTrail(points, releasedAtMillis + LASER_TRAIL_DURATION_MILLIS))
}

internal fun expireLaserTrails(trails: MutableList<LaserTrail>, nowMillis: Long) {
    trails.removeAll { nowMillis >= it.expiresAtMillis }
}

@Composable
fun PresentationControlBar(
    title: String,
    currentPage: Int,
    totalPages: Int,
    elapsedSeconds: Long,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    isBlackout: Boolean,
    isLaserPointerEnabled: Boolean,
    isPenEnabled: Boolean = false,
    canClearAnnotations: Boolean = false,
    onExit: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleBlackout: () -> Unit,
    onToggleLaserPointer: () -> Unit,
    onTogglePen: () -> Unit = {},
    onClearAnnotations: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.widthIn(max = 760.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xE61B1B20),
        contentColor = Color.White,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onExit, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Salir de la presentación")
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Página $currentPage de $totalPages",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                    Text(
                        text = "  ·  ${elapsedSeconds / 60}:${(elapsedSeconds % 60).toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1
                    )
                }
            }

            IconButton(onClick = onPreviousPage, enabled = canGoPrevious, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Página anterior")
            }
            IconButton(onClick = onNextPage, enabled = canGoNext, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Página siguiente")
            }
            IconButton(onClick = onToggleBlackout, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = if (isBlackout) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (isBlackout) "Mostrar diapositiva" else "Poner pantalla en negro"
                )
            }
            IconButton(
                onClick = onToggleLaserPointer,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (isLaserPointerEnabled) Color(0xFFFF2E2E).copy(alpha = 0.22f) else Color.Transparent,
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.GpsFixed,
                    contentDescription = if (isLaserPointerEnabled) "Desactivar puntero láser" else "Activar puntero láser",
                    tint = if (isLaserPointerEnabled) Color(0xFFFF6B6B) else Color.White
                )
            }
            IconButton(
                onClick = onTogglePen,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (isPenEnabled) Color(0xFF4D9FFF).copy(alpha = 0.22f) else Color.Transparent,
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Draw,
                    contentDescription = if (isPenEnabled) "Desactivar anotaciones a mano" else "Activar anotaciones a mano",
                    tint = if (isPenEnabled) Color(0xFF64B5F6) else Color.White
                )
            }
            IconButton(
                onClick = onClearAnnotations,
                enabled = canClearAnnotations,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "Borrar anotaciones de esta página",
                    tint = if (canClearAnnotations) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.38f)
                )
            }
        }
    }
}

@Composable
fun PresentationLaserPointer(
    enabled: Boolean,
    position: Offset? = null,
    onPositionChanged: (Offset?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val updatePosition by rememberUpdatedState(onPositionChanged)
    var currentPoint by remember { mutableStateOf<Offset?>(null) }
    var isPressed by remember { mutableStateOf(false) }
    val activeTrailPoints = remember { mutableStateListOf<Offset>() }
    val trails = remember { mutableStateListOf<LaserTrail>() }

    LaunchedEffect(enabled, trails.size) {
        if (!enabled) {
            isPressed = false
            currentPoint = null
            updatePosition(null)
            activeTrailPoints.clear()
            trails.clear()
        } else if (trails.isNotEmpty()) {
            val now = SystemClock.uptimeMillis()
            val nextExpiry = trails.minOf { it.expiresAtMillis }
            delay((nextExpiry - now).coerceAtLeast(0L))
            expireLaserTrails(trails, SystemClock.uptimeMillis())
        }
    }

    val inputModifier = if (enabled) {
        Modifier.pointerInput(enabled) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.type == PointerType.Touch || down.type == PointerType.Stylus) {
                    isPressed = true
                    currentPoint = down.position
                    updatePosition(down.position)
                    activeTrailPoints.clear()
                    activeTrailPoints.add(down.position)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if (change.type == PointerType.Touch || change.type == PointerType.Stylus) {
                                val pos = change.position
                                val last = activeTrailPoints.lastOrNull()
                                if (last == null || (pos - last).getDistanceSquared() > 4f) {
                                    activeTrailPoints.add(pos)
                                }
                                currentPoint = pos
                                updatePosition(pos)
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    addLaserTrail(
                        trails = trails,
                        points = activeTrailPoints.toList(),
                        releasedAtMillis = SystemClock.uptimeMillis()
                    )
                    activeTrailPoints.clear()
                    isPressed = false
                    currentPoint = null
                    updatePosition(null)
                }
            }
        }
    } else {
        Modifier
    }

    Canvas(modifier = modifier.fillMaxSize().then(inputModifier)) {
        trails.forEach { trail -> drawLaserTrail(trail.points) }
        if (activeTrailPoints.isNotEmpty()) {
            drawLaserTrail(activeTrailPoints)
        }

        val activePoint = if (isPressed) (currentPoint ?: position) else null
        activePoint?.let { point ->
            val radius = 12.dp.toPx()
            drawCircle(Color.Red.copy(alpha = 0.25f), radius = radius * 2.2f, center = point)
            drawCircle(Color(0xFFFF2E2E), radius = radius, center = point)
            drawCircle(Color.White, radius = radius * 0.35f, center = point)
        }
    }
}

private fun DrawScope.drawLaserTrail(points: List<Offset>) {
    if (points.size == 1) {
        val point = points.single()
        drawCircle(Color.Red.copy(alpha = 0.25f), radius = 16.dp.toPx(), center = point)
        drawCircle(Color(0xFFFF2E2E).copy(alpha = 0.85f), radius = 6.dp.toPx(), center = point)
        return
    }
    if (points.size < 2) return

    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val current = points[i]
            quadraticTo(previous.x, previous.y, (previous.x + current.x) / 2f, (previous.y + current.y) / 2f)
        }
        lineTo(points.last().x, points.last().y)
    }
    drawPath(
        path = path,
        color = Color.Red.copy(alpha = 0.28f),
        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawPath(
        path = path,
        color = Color(0xFFFF2E2E).copy(alpha = 0.90f),
        style = Stroke(width = 3.5f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawPath(
        path = path,
        color = Color.White.copy(alpha = 0.60f),
        style = Stroke(width = 1.2f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

@Composable
fun PresentationAnnotationLayer(
    enabled: Boolean,
    strokes: List<PresentationStroke>,
    currentColor: Color,
    strokeWidth: Float,
    onStrokeFinished: (PresentationStroke) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentPoints = remember { mutableStateListOf<Offset>() }
    val updateStrokeFinished by rememberUpdatedState(onStrokeFinished)

    val inputModifier = if (enabled) {
        Modifier.pointerInput(enabled, currentColor, strokeWidth) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.type == PointerType.Touch || down.type == PointerType.Stylus) {
                    currentPoints.clear()
                    currentPoints.add(down.position)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if (change.type == PointerType.Touch || change.type == PointerType.Stylus) {
                                val pos = change.position
                                val last = currentPoints.lastOrNull()
                                if (last == null || (pos - last).getDistanceSquared() > 4f) {
                                    currentPoints.add(pos)
                                }
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (currentPoints.isNotEmpty()) {
                        updateStrokeFinished(
                            PresentationStroke(
                                points = currentPoints.toList(),
                                color = currentColor,
                                strokeWidth = strokeWidth
                            )
                        )
                        currentPoints.clear()
                    }
                }
            }
        }
    } else {
        Modifier
    }

    Canvas(modifier = modifier.fillMaxSize().then(inputModifier)) {
        for (stroke in strokes) {
            drawPresentationStroke(stroke.points, stroke.color, stroke.strokeWidth)
        }
        if (currentPoints.isNotEmpty()) {
            drawPresentationStroke(currentPoints, currentColor, strokeWidth)
        }
    }
}

private fun DrawScope.drawPresentationStroke(
    points: List<Offset>,
    color: Color,
    width: Float
) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color, radius = width / 2f, center = points[0])
        return
    }
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            quadraticTo(prev.x, prev.y, (prev.x + curr.x) / 2f, (prev.y + curr.y) / 2f)
        }
        lineTo(points.last().x, points.last().y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = width,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}
