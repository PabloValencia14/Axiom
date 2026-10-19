package org.readera.openreadera.ui.reader.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.readera.openreadera.data.model.DrawingStroke
import org.readera.openreadera.engine.PageTextRange
import org.readera.openreadera.engine.PageTextLayout
import org.readera.openreadera.engine.PdfReferenceText
import org.readera.openreadera.papers.CitationTarget

enum class DrawingTool {
    PEN,
    MARKER,
    FREE_HIGHLIGHTER,
    HIGHLIGHTER,
    ERASER
}

@Composable
fun StylusInkingLayer(
    modifier: Modifier = Modifier,
    page: Int,
    savedStrokes: List<DrawingStroke>,
    currentTool: DrawingTool,
    currentColorHex: String,
    currentStrokeWidth: Float,
    onStrokeFinished: (DrawingStroke) -> Unit,
    onDeleteStroke: (DrawingStroke) -> Unit,
    pageAspectRatio: Float = 0f,
    pageTextRange: PageTextRange? = null,
    pageTextLayout: PageTextLayout? = null,
    writingMode: Boolean = false,
    inputScale: Float = 1f,
    onPageActive: (Int) -> Unit = {},
    removedStrokes: List<DrawingStroke> = emptyList(),
    onRemovedStrokesApplied: (List<DrawingStroke>) -> Unit = {},
    onCitation: ((CitationTarget) -> Unit)? = null
) {
    val openCitation by rememberUpdatedState(onCitation)
    // Compose coordinates follow the page transform even when native View hit bounds are clipped by zoom.
    val references = if (writingMode || onCitation == null || pageTextLayout == null) Modifier else Modifier
        .pointerInput(page, pageAspectRatio, pageTextLayout) {
            coroutineScope {
                var hover: CitationTarget? = null
                var pendingHover: Job? = null
                var pressed: CitationTarget? = null
                var down = Offset.Zero
                var cancelled = false
                fun citationAt(position: Offset): CitationTarget? {
                    val rect = PageDrawingGeometry.fitRect(size.width.toFloat(), size.height.toFloat(), pageAspectRatio)
                    if (rect.width <= 0f || rect.height <= 0f) return null
                    val x = (position.x - rect.left) / rect.width
                    val y = (position.y - rect.top) / rect.height
                    val word = pageTextLayout.words.firstOrNull { it.bounds.contains(x, y) } ?: return null
                    return PdfReferenceText.citationAt(pageTextLayout, word)
                }
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            val target = if (event.type == PointerEventType.Exit) null else citationAt(change.position)
                            val hovering = !change.pressed && !change.previousPressed &&
                                change.type != PointerType.Touch && event.type in listOf(PointerEventType.Enter, PointerEventType.Move, PointerEventType.Exit)
                            if (change.pressed || hovering && (event.type == PointerEventType.Exit || target != hover)) {
                                pendingHover?.cancel()
                                hover = if (hovering) target else null
                                pendingHover = hover?.let { value -> launch { delay(450); openCitation?.invoke(value) } }
                            }
                            if (change.pressed && !change.previousPressed) {
                                pressed = target
                                down = change.position
                                cancelled = false
                            }
                            if (pressed != null) {
                                if (event.changes.size > 1 || (change.position - down).getDistance() > viewConfiguration.touchSlop)
                                    cancelled = true
                                if (change.isConsumed) cancelled = true
                                change.consume()
                                if (!change.pressed) {
                                    if (!cancelled) openCitation?.invoke(pressed!!)
                                    pressed = null
                                }
                            }
                        }
                    }
                } finally { pendingHover?.cancel() }
            }
        }
    Box(modifier.fillMaxSize().then(references)) {
        key(page, pageAspectRatio, pageTextRange) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ReaderInkView(it) },
                onRelease = { it.release() },
                update = { view ->
                    view.configure(
                        page, savedStrokes, currentTool, currentColorHex, currentStrokeWidth,
                        onStrokeFinished, onDeleteStroke, pageAspectRatio, pageTextRange, pageTextLayout,
                        onPageActive, removedStrokes, onRemovedStrokesApplied, writingMode, inputScale
                    )
                }
            )
        }
    }
}
