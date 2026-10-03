package org.readera.openreadera.ui.reader.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.SelfOverlap
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import org.readera.openreadera.data.model.DrawingPageAnchor
import org.readera.openreadera.data.model.DrawingStroke
import org.readera.openreadera.data.model.StrokePoint
import org.readera.openreadera.data.model.StrokeToolType
import org.readera.openreadera.engine.PageTextRange
import org.readera.openreadera.engine.PageTextLayout
import kotlin.math.ceil
import kotlin.math.hypot

/** Only the page/persistence, rectangular highlight and whole-stroke eraser policies live here.
 * Ink owns pen modeling, tessellation, wet rendering and completed stroke rendering.
 */
internal class ReaderInkView(context: Context) : FrameLayout(context) {
    private data class DryStroke(
        val record: DrawingStroke,
        val points: List<InkPoint>,
        val rectangles: List<InkRect>,
        val ink: Stroke?
    )
    private data class PendingStroke(
        val record: DrawingStroke,
        var ink: Stroke? = null,
        var observedSaved: Boolean = false
    )
    private data class Gesture(
        val pointerId: Int,
        val tool: DrawingTool,
        val page: Int,
        val color: String,
        val width: Float,
        val rect: InkRect,
        val anchor: DrawingPageAnchor?,
        val finish: (DrawingStroke) -> Unit,
        val delete: (DrawingStroke) -> Unit,
        val startTime: Long,
        val brush: Brush?,
        var inkId: InProgressStrokeId? = null,
        var lastPoint: InkPoint? = null,
        val points: MutableList<StrokePoint> = ArrayList(),
        val words: List<InkTextWord>,
        val highlightedWords: MutableSet<Int> = LinkedHashSet()
    )

    private val renderer = CanvasStrokeRenderer.create()
    private val wet = InProgressStrokesView(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val transform = Matrix()
    private val scratchInput = StrokeInput()
    private val moveBatch = MutableStrokeInputBatch()
    private val pending = LinkedHashMap<InProgressStrokeId, PendingStroke>()
    private val erased = HashSet<Long>()
    private var gesture: Gesture? = null
    private var page = -1
    private var aspect = 0f
    private var rect = InkRect(0f, 0f, 0f, 0f)
    private var tool = DrawingTool.PEN
    private var color = "#FACC15"
    private var strokeWidth = 4f
    private var anchor: DrawingPageAnchor? = null
    private var textWords: List<InkTextWord> = emptyList()
    private var appliedTextLayout: PageTextLayout? = null
    private var writingMode = false
    private var inputScale = 1f
    private var records: List<DrawingStroke> = emptyList()
    private var dryStrokes: List<DryStroke> = emptyList()
    private var finish: (DrawingStroke) -> Unit = {}
    private var delete: (DrawingStroke) -> Unit = {}
    private var activatePage: (Int) -> Unit = {}
    private val dry = object : View(context) {
        override fun onDraw(canvas: Canvas) = drawDry(canvas)
    }
    private val listener = object : InProgressStrokesFinishedListener {
        override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
            // Persistence already happened at UP, with the originating page/book callback.
            // Handoff is purely visual and must not write through a new page's callback.
            for ((id, stroke) in strokes) pending[id]?.ink = stroke
            reconcilePending()
            dry.invalidate()
            wet.removeFinishedStrokes(strokes.keys)
        }
    }

    init {
        clipChildren = true
        addView(dry, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        wet.addFinishedStrokesListener(listener)
    }

    fun configure(
        page: Int, saved: List<DrawingStroke>, tool: DrawingTool, color: String, width: Float,
        finish: (DrawingStroke) -> Unit, delete: (DrawingStroke) -> Unit, aspect: Float,
        range: PageTextRange?, textLayout: PageTextLayout?, activatePage: (Int) -> Unit,
        removed: List<DrawingStroke>, removedApplied: (List<DrawingStroke>) -> Unit,
        writingMode: Boolean, inputScale: Float
    ) {
        require(inputScale.isFinite() && inputScale > 0f)
        val changedPage = this.page != page || this.aspect != aspect || this.anchor != range?.let {
            DrawingPageAnchor(it.chapterIndex, it.startOffset)
        }
        if (changedPage || this.tool != tool || this.color != color || strokeWidth != width ||
            this.writingMode != writingMode || this.inputScale != inputScale) cancelGesture()
        if (changedPage) {
            pending.clear()
            erased.clear()
        }
        this.page = page
        this.aspect = aspect
        this.tool = tool
        this.color = color
        this.writingMode = writingMode
        this.inputScale = inputScale
        strokeWidth = width
        this.finish = finish
        this.delete = delete
        this.activatePage = activatePage
        anchor = range?.let { DrawingPageAnchor(it.chapterIndex, it.startOffset) }
        if (appliedTextLayout !== textLayout) {
            textWords = textLayout?.words.orEmpty().map {
                InkTextWord(InkRect(it.bounds.left, it.bounds.top, it.bounds.width(), it.bounds.height()), it.lineIndex)
            }
            appliedTextLayout = textLayout
        }
        val changedRecords = records != saved
        // A save and deletion can be conflated into one Compose frame. Retire its wet
        // stroke explicitly, even when that frame never contained the saved record.
        val removedIds = pending.filterValues { value -> removed.any { matches(it, value.record) } }.keys
        if (removedIds.isNotEmpty()) {
            wet.removeFinishedStrokes(removedIds)
            removedIds.forEach(pending::remove)
        }
        records = saved
        if (changedPage || changedRecords) rebuildDry()
        reconcilePending()
        dry.invalidate()
        if (removed.isNotEmpty()) removedApplied(removed)
        erased.removeAll { id -> saved.none { it.id == id } }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) cancelGesture()
        rebuildDry()
    }

    private fun updateTransform() {
        rect = PageDrawingGeometry.fitRect(width.toFloat(), height.toFloat(), aspect)
        transform.setScale(rect.width / 600f, rect.width / 600f)
        transform.postTranslate(rect.left, rect.top)
    }

    private fun brush(hex: String, width: Float, tool: StrokeToolType): Brush =
        Brush.createWithColorIntArgb(
            family = when (tool) {
                StrokeToolType.PEN -> StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
                StrokeToolType.MARKER, StrokeToolType.HIGHLIGHTER -> StockBrushes.marker(StockBrushes.MarkerVersion.V1)
                StrokeToolType.FREE_HIGHLIGHTER -> StockBrushes.highlighter(
                    selfOverlap = SelfOverlap.DISCARD, version = StockBrushes.HighlighterVersion.V1)
            },
            colorIntArgb = (parseColor(hex) and 0x00ffffff) or
                ((if (tool == StrokeToolType.FREE_HIGHLIGHTER || tool == StrokeToolType.HIGHLIGHTER) 97 else 255) shl 24),
            size = (width * (rect.width.coerceIn(600f, 1800f) / rect.width)).coerceAtLeast(0.1f),
            epsilon = 0.1f
        )

    private fun storedTool(tool: DrawingTool): StrokeToolType = when (tool) {
        DrawingTool.PEN -> StrokeToolType.PEN
        DrawingTool.MARKER -> StrokeToolType.MARKER
        DrawingTool.FREE_HIGHLIGHTER -> StrokeToolType.FREE_HIGHLIGHTER
        DrawingTool.HIGHLIGHTER -> StrokeToolType.HIGHLIGHTER
        DrawingTool.ERASER -> error("An eraser deletes strokes; it is not a stored brush")
    }

    private fun rebuildDry() {
        updateTransform()
        if (rect.width <= 0f || rect.height <= 0f) {
            dryStrokes = emptyList()
            return
        }
        dryStrokes = records.mapNotNull { record ->
            val data = record.parseData()
            if (data.points.isEmpty()) return@mapNotNull null
            val positions = data.points.map { point ->
                if (data.isPageNormalized) rect.denormalize(InkPoint(point.x, point.y))
                else InkPoint(
                    if (point.x <= 1.05f) point.x * width else point.x,
                    if (point.y <= 1.05f) point.y * height else point.y
                )
            }
            val rectangles = if (data.isRectangularHighlight) positions.chunked(2).mapNotNull { pair ->
                if (pair.size != 2) null else InkRect(
                    minOf(pair[0].x, pair[1].x), minOf(pair[0].y, pair[1].y),
                    kotlin.math.abs(pair[1].x - pair[0].x), kotlin.math.abs(pair[1].y - pair[0].y)
                )
            } else emptyList()
            val ink = if (data.isRectangularHighlight) null else {
                val batch = MutableStrokeInputBatch()
                data.points.forEachIndexed { index, point ->
                    // Use the exact live transform and clock; only clockless legacy inputs get synthetic timing.
                    updateStrokeInput(scratchInput, point, rect, point.elapsedTimeMillis ?: index * 8L)
                    batch.add(scratchInput)
                }
                Stroke(brush(record.colorHex, record.strokeWidth, record.toolType), batch)
            }
            DryStroke(record, positions, rectangles, ink)
        }
        dry.invalidate()
    }

    private fun matches(a: DrawingStroke, b: DrawingStroke): Boolean =
        a.page == b.page && a.createdAt == b.createdAt && a.pointsJson == b.pointsJson &&
            a.toolType == b.toolType && a.colorHex == b.colorHex && a.strokeWidth == b.strokeWidth

    private fun reconcilePending() {
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val (id, value) = iterator.next()
            val saved = records.any { matches(it, value.record) }
            if ((saved && value.ink != null) || (!saved && value.observedSaved)) {
                wet.removeFinishedStrokes(setOf(id))
                iterator.remove()
            } else if (saved) value.observedSaved = true
        }
    }

    private fun drawDry(canvas: Canvas) {
        for (stroke in dryStrokes) {
            if (stroke.record.id in erased || pending.values.any { it.ink == null && matches(it.record, stroke.record) }) continue
            drawRectangles(canvas, stroke.rectangles, stroke.record.colorHex)
            stroke.ink?.let { drawInk(canvas, it) }
        }
        for (stroke in pending.values) stroke.ink?.let { drawInk(canvas, it) }
        gesture?.takeIf { it.tool == DrawingTool.HIGHLIGHTER }?.let {
            drawRectangles(canvas, highlightRectangles(it), it.color)
        }
    }

    private fun drawInk(canvas: Canvas, stroke: Stroke) {
        val checkpoint = canvas.save()
        canvas.concat(transform)
        renderer.draw(canvas, stroke, transform)
        canvas.restoreToCount(checkpoint)
    }

    private fun drawRectangles(canvas: Canvas, rectangles: List<InkRect>, color: String) {
        paint.color = (parseColor(color) and 0x00ffffff) or (97 shl 24)
        for (r in rectangles) canvas.drawRect(r.left, r.top, r.right, r.bottom, paint)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val consumed = gesture != null
            cancelGesture()
            return consumed
        }
        val actionIndex = event.actionIndex
        if (!writingMode) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            val inputTool = event.getToolType(actionIndex)
            if (inputTool == MotionEvent.TOOL_TYPE_STYLUS || inputTool == MotionEvent.TOOL_TYPE_ERASER) {
                cancelGesture()
                val point = InkPoint(event.getX(actionIndex) / inputScale, event.getY(actionIndex) / inputScale)
                if (!rect.contains(point) || rect.width <= 0f || rect.height <= 0f) return false
                val selectedTool = if (inputTool == MotionEvent.TOOL_TYPE_ERASER ||
                    (event.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0)
                    DrawingTool.ERASER else tool
                val g = Gesture(event.getPointerId(actionIndex), selectedTool, page, color, strokeWidth, rect,
                    anchor, finish, delete, event.eventTime,
                    if (selectedTool != DrawingTool.HIGHLIGHTER && selectedTool != DrawingTool.ERASER)
                        brush(color, strokeWidth, storedTool(selectedTool)) else null,
                    words = textWords)
                gesture = g
                activatePage(page)
                parent?.requestDisallowInterceptTouchEvent(true)
                requestUnbufferedDispatch(event)
                sample(g, event, actionIndex, -1)
                return true
            }
        }
        val g = gesture ?: return false // Finger gestures remain available to Compose navigation.
        val index = event.findPointerIndex(g.pointerId)
        if (index < 0) {
            cancelGesture()
            return true
        }
        val ending = event.actionMasked == MotionEvent.ACTION_UP ||
            (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(actionIndex) == g.pointerId)
        if (ending && (event.flags and MotionEvent.FLAG_CANCELED) != 0) {
            cancelGesture()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE || ending) {
            moveBatch.clear()
            for (history in 0 until event.historySize) sample(g, event, index, history)
            sample(g, event, index, -1, appendToBatch = !ending)
            if (g.brush != null && moveBatch.size > 0) {
                wet.addToStroke(moveBatch, checkNotNull(g.inkId))
            }
            if (ending) finishGesture(g)
        }
        return true
    }

    private fun sample(g: Gesture, event: MotionEvent, index: Int, history: Int, appendToBatch: Boolean = true) {
        val x = if (history < 0) event.getX(index) else event.getHistoricalX(index, history)
        val y = if (history < 0) event.getY(index) else event.getHistoricalY(index, history)
        if (!x.isFinite() || !y.isFinite()) return
        // AndroidView receives coordinates relative to the scaled origin, without inverse graphicsLayer scale.
        val point = g.rect.clamp(InkPoint(x / inputScale, y / inputScale))
        if (g.tool == DrawingTool.ERASER) {
            eraseSweep(g, g.lastPoint ?: point, point)
            g.lastPoint = point
            return
        }
        val normalized = g.rect.normalize(point)
        val pressure = if (history < 0) event.getPressure(index) else event.getHistoricalPressure(index, history)
        val time = if (history < 0) event.eventTime else event.getHistoricalEventTime(history)
        val elapsed = (time - g.startTime).coerceAtLeast(0L)
        if (elapsed < (g.points.lastOrNull()?.elapsedTimeMillis ?: 0L)) return
        // Ink accepts equal times, but not duplicate x-y-t triplets. Never invent sensor timing.
        for (index in g.points.indices.reversed()) {
            val previous = g.points[index]
            if (previous.elapsedTimeMillis != elapsed) break
            if (previous.x == normalized.x && previous.y == normalized.y) return
        }
        val saved = StrokePoint(normalized.x, normalized.y,
            (if (pressure.isFinite()) pressure else 1f).coerceIn(0f, 1f), elapsed)
        g.points.add(saved)
        if (g.brush != null) {
            if (g.inkId == null) {
                // Ink queues start/end inputs by reference on its render thread; transfer ownership.
                val firstInput = updateStrokeInput(StrokeInput(), saved, g.rect)
                g.inkId = wet.startStroke(firstInput, checkNotNull(g.brush), transform)
            } else if (appendToBatch) {
                moveBatch.add(updateStrokeInput(scratchInput, saved, g.rect))
            }
        } else {
            g.highlightedWords.addAll(PageDrawingGeometry.touchedWordsForSegment(
                g.lastPoint ?: point, point, g.rect, g.words))
            dry.invalidate()
        }
        g.lastPoint = point
    }

    private fun updateStrokeInput(
        out: StrokeInput, point: StrokePoint, pageRect: InkRect,
        elapsedTimeMillis: Long = checkNotNull(point.elapsedTimeMillis)
    ): StrokeInput {
        out.update(point.x * 600f, point.y * pageRect.height * 600f / pageRect.width,
            elapsedTimeMillis, InputToolType.STYLUS, pressure = point.pressure)
        return out
    }

    private fun highlightRectangles(g: Gesture): List<InkRect> =
        PageDrawingGeometry.textHighlightBands(g.words, g.highlightedWords, g.rect)

    private fun finishGesture(g: Gesture) {
        if (g.tool != DrawingTool.ERASER && g.points.isNotEmpty() &&
            (g.tool != DrawingTool.HIGHLIGHTER || g.highlightedWords.isNotEmpty())) {
            val highlighter = g.tool == DrawingTool.HIGHLIGHTER
            val json = if (highlighter) DrawingStroke.serializeRectangularHighlights(
                highlightRectangles(g).flatMap { r -> listOf(g.rect.normalize(InkPoint(r.left, r.top)),
                    g.rect.normalize(InkPoint(r.right, r.bottom))) }.map { StrokePoint(it.x, it.y) }, g.anchor)
            else DrawingStroke.serializeTimedPoints(g.points, g.anchor)
            val record = DrawingStroke(bookId = 0L, page = g.page,
                toolType = storedTool(g.tool),
                colorHex = g.color, strokeWidth = g.width, pointsJson = json)
            g.inkId?.let { id ->
                pending[id] = PendingStroke(record)
                wet.finishStroke(updateStrokeInput(StrokeInput(), g.points.last(), g.rect), id)
            }
            g.finish(record)
        }
        gesture = null
        parent?.requestDisallowInterceptTouchEvent(false)
        dry.invalidate()
    }

    private fun eraseSweep(g: Gesture, start: InkPoint, end: InkPoint) {
        val radius = 36f * (g.rect.width / 600f).coerceIn(1f, 3f)
        val steps = ceil(hypot(end.x - start.x, end.y - start.y) / (radius * 0.5f)).toInt().coerceAtLeast(1)
        for (stroke in dryStrokes) {
            if (stroke.record.id in erased) continue
            var hit = false
            for (index in 0..steps) {
                val t = index.toFloat() / steps
                val p = InkPoint(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t)
                if (stroke.rectangles.any { r -> p.x in (r.left - radius)..(r.right + radius) &&
                        p.y in (r.top - radius)..(r.bottom + radius) } ||
                    PageDrawingGeometry.intersectsStroke(p, stroke.points, radius)) {
                    hit = true
                    break
                }
            }
            if (hit) {
                erased.add(stroke.record.id)
                g.delete(stroke.record)
            }
        }
        dry.invalidate()
    }

    private fun cancelGesture() {
        gesture?.inkId?.let { wet.cancelStroke(it) }
        gesture = null
        parent?.requestDisallowInterceptTouchEvent(false)
        dry.invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelGesture()
        super.onDetachedFromWindow()
    }

    fun release() {
        cancelGesture()
        wet.removeFinishedStrokes(wet.getFinishedStrokes().keys)
        wet.removeFinishedStrokesListener(listener)
        pending.clear()
    }

    private fun parseColor(hex: String): Int = try { Color.parseColor(hex) }
        catch (_: IllegalArgumentException) { Color.YELLOW }
}
