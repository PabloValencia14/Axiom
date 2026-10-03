package org.readera.openreadera.ui.reader.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.readera.openreadera.data.model.DrawingStroke
import org.readera.openreadera.engine.PageTextRange
import org.readera.openreadera.engine.PageTextLayout

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
    onRemovedStrokesApplied: (List<DrawingStroke>) -> Unit = {}
) {
    key(page, pageAspectRatio, pageTextRange) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
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
