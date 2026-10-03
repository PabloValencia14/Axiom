package org.readera.openreadera.ui.reader.components

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.Rect
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readera.openreadera.data.db.AppDatabase
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.AppThemeMode
import org.readera.openreadera.data.model.ReaderColorTheme
import org.readera.openreadera.data.model.ReaderViewMode
import org.readera.openreadera.data.model.StrokeToolType
import org.readera.openreadera.data.preferences.ReaderPreferences
import org.readera.openreadera.data.repository.BookRepository
import org.readera.openreadera.engine.EngineManager
import org.readera.openreadera.ui.reader.ReaderScreen
import org.readera.openreadera.ui.reader.ReaderViewModel
import org.readera.openreadera.ui.theme.OpenReadEraTheme
import java.io.File

/** Real reader mounts + native Ink + persistent Room, entirely on test-owned data.
 * Synthetic stylus samples prove routing/pressure storage, not physical pen or palm quality.
 */
@RunWith(AndroidJUnit4::class)
class ReaderInkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun awaitCondition(block: () -> Boolean) {
        val caller = Throwable("Reader wait callsite")
        try {
            runBlocking { withTimeout(30_000) { while (!block()) delay(30) } }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Reader condition timed out", failure).apply { addSuppressed(caller) }
        }
    }

    private fun finishedInk(view: ReaderInkView): java.util.concurrent.atomic.AtomicReference<androidx.ink.strokes.Stroke?> {
        val result = java.util.concurrent.atomic.AtomicReference<androidx.ink.strokes.Stroke?>()
        main {
            (view.getChildAt(1) as androidx.ink.authoring.InProgressStrokesView).addFinishedStrokesListener(
                object : androidx.ink.authoring.InProgressStrokesFinishedListener {
                    override fun onStrokesFinished(strokes: Map<androidx.ink.authoring.InProgressStrokeId, androidx.ink.strokes.Stroke>) {
                        result.set(strokes.values.last())
                    }
                }
            )
        }
        return result
    }

    private fun overlays(view: View): List<ReaderInkView> = when (view) {
        is ReaderInkView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { overlays(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun findNode(label: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                search(child)?.let { return it }
            }
            return null
        }
        return instrumentation.uiAutomation.windows.firstNotNullOfOrNull { it.root?.let(::search) }
            ?: instrumentation.uiAutomation.rootInActiveWindow?.let(::search)
    }

    private fun node(label: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        awaitCondition { result = findNode(label); result != null }
        val named = checkNotNull(result)
        // Compose exposes descriptive children separately from their actionable parent.
        var action: AccessibilityNodeInfo? = named
        while (action != null) {
            action.refresh()
            if (action.isClickable) return action
            action = action.parent
        }
        return named
    }

    private fun saveAccessibilityEvidence() {
        val text = StringBuilder()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            val bounds = Rect().also(node::getBoundsInScreen)
            text.append(" ".repeat(depth)).append("text=").append(node.text)
                .append(" desc=").append(node.contentDescription)
                .append(" enabled=").append(node.isEnabled).append(" clickable=").append(node.isClickable)
                .append(" visible=").append(node.isVisibleToUser).append(" bounds=").append(bounds)
                .append(" actions=").append(node.actionList).append('\n')
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
        }
        for (window in instrumentation.uiAutomation.windows) {
            text.append("WINDOW ").append(window.id).append(" type=").append(window.type).append('\n')
            window.root?.let { visit(it, 0) }
        }
        File(instrumentation.targetContext.getExternalFilesDir(null), "ink-accessibility.txt").writeText(text.toString())
    }

    private fun tapNode(label: String, stylus: Boolean = false) {
        instrumentation.uiAutomation.waitForIdle(150, 5_000)
        var target = node(label)
        for (attempt in 0..10) {
            val targetBounds = Rect().also(target::getBoundsInScreen)
            var ancestor = target.parent
            while (ancestor != null && !ancestor.isScrollable) ancestor = ancestor.parent
            val scrollBounds = ancestor?.let { Rect().also(it::getBoundsInScreen) }
            if (target.isVisibleToUser && !targetBounds.isEmpty &&
                (scrollBounds == null || scrollBounds.contains(targetBounds.centerX(), targetBounds.centerY()))) break
            if (ancestor == null) break
            ancestor.performAction(if (targetBounds.centerY() < checkNotNull(scrollBounds).top)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            instrumentation.uiAutomation.waitForIdle(150, 5_000)
            target = node(label)
        }
        assertTrue("$label remains attached", target.refresh())
        assertTrue("$label is enabled", target.isEnabled)
        val bounds = Rect()
        target.getBoundsInScreen(bounds)
        assertFalse("$label has visible bounds", bounds.isEmpty)
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val pointer = MotionEvent.PointerProperties().apply {
                id = 0; toolType = if (stylus) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER
            }
            val point = MotionEvent.PointerCoords().apply {
                x = bounds.exactCenterX(); y = bounds.exactCenterY(); pressure = 1f; size = 0.01f
            }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, 1,
                arrayOf(pointer), arrayOf(point), 0, 0, 1f, 1f, 0, 0,
                if (stylus) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN, 0)
            assertTrue("Inject $label", instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private fun dismissMenu() {
        tapNode("Cerrar escritura")
        awaitCondition { findNode("Pluma") == null }
    }

    private fun renderedInk(view: ReaderInkView): List<androidx.ink.strokes.Stroke> {
        val field = ReaderInkView::class.java.getDeclaredField("dryStrokes").apply { isAccessible = true }
        var result = emptyList<androidx.ink.strokes.Stroke>()
        main {
            result = (field.get(view) as List<*>).mapNotNull { value ->
                checkNotNull(value).javaClass.getDeclaredField("ink").apply { isAccessible = true }
                    .get(value) as? androidx.ink.strokes.Stroke
            }
        }
        return result
    }

    private fun fixture(context: Context): File {
        val file = File.createTempFile("ink-reader-", ".pdf", context.cacheDir)
        val document = PdfDocument()
        repeat(2) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 850, index + 1).create())
            page.canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK; textSize = 18f }
            page.canvas.drawText("Prueba aislada de tinta / pagina ${index + 1}", 40f, 90f, paint)
            page.canvas.drawText("Resaltador: esta linea debe conservarse al reabrir.", 40f, 230f, paint)
            document.finishPage(page)
        }
        file.outputStream().use(document::writeTo)
        document.close()
        return file
    }

    private fun stroke(view: ReaderInkView, x1: Float, y1: Float, x2: Float, y2: Float,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS, canceled: Boolean = false, palm: Boolean = false,
        duringStroke: (() -> Unit)? = null, constantPressure: Float? = null, sampleTimes: List<Long>? = null) {
        val location = IntArray(2)
        main { view.getLocationOnScreen(location) }
        val rect = PageDrawingGeometry.fitRect(view.width.toFloat(), view.height.toFloat(), 600f / 850f)
        val startTime = SystemClock.uptimeMillis()
        for (step in 0..8) {
            if (step == 4) duringStroke?.invoke()
            val t = step / 8f
            val pointer = MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType }
            val coordinates = MotionEvent.PointerCoords().apply {
                x = location[0] + rect.left + (x1 + (x2 - x1) * t) * rect.width
                y = location[1] + rect.top + (y1 + (y2 - y1) * t) * rect.height
                pressure = constantPressure ?: (0.2f + t * 0.7f)
                size = 0.01f
            }
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 8 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val palmPointer = MotionEvent.PointerProperties().apply { id = 1; this.toolType = MotionEvent.TOOL_TYPE_FINGER }
            val palmCoordinates = MotionEvent.PointerCoords().apply {
                x = location[0] + rect.left + rect.width * 0.9f
                y = location[1] + rect.top + rect.height * 0.9f
                pressure = 1f; size = 0.3f
            }
            val mixed = palm && step in 3..6
            val mixedAction = when {
                palm && step == 3 -> MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                palm && step == 6 -> MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                else -> action
            }
            val event = MotionEvent.obtain(startTime, sampleTimes?.get(step)?.let { startTime + it }
                ?: SystemClock.uptimeMillis(), mixedAction, if (mixed) 2 else 1,
                if (mixed) arrayOf(pointer, palmPointer) else arrayOf(pointer),
                if (mixed) arrayOf(coordinates, palmCoordinates) else arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_STYLUS, if (canceled && step == 8) MotionEvent.FLAG_CANCELED else 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
            SystemClock.sleep(12)
        }
        instrumentation.waitForIdleSync()
    }

    private fun waitForFrame(view: View) {
        val frame = java.util.concurrent.CountDownLatch(1)
        main { view.postOnAnimation { frame.countDown() } }
        assertTrue("Reader layout frame completes", frame.await(3, java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun fingerTap(view: ReaderInkView) {
        val location = IntArray(2)
        main { view.getLocationOnScreen(location) }
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                location[0] + view.width / 2f, location[1] + view.height / 2f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Ink proof: ${output.path}\n") })
    }

    @Test
    fun readerInkSurvivesHandoffPageSwitchEraseUndoAndDatabaseReopen() = runBlocking {
        val target = instrumentation.targetContext
        val prefix = "ink_regression_${System.nanoTime()}_"
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val isolated = prefix + name
                synchronized(names) { names.add(isolated) }
                return super.getSharedPreferences(isolated, mode)
            }
        }
        context.getSharedPreferences("google_sync_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("explicitly_signed_out", true).commit()
        context.getSharedPreferences("drive_sync_data", Context.MODE_PRIVATE).edit()
            .putBoolean("auto_sync_enabled", false).commit()
        val file = fixture(context)
        val databaseName = prefix + "reader.db"
        var database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        fun repository() = BookRepository(database.bookDao(), database.bookmarkDao(), database.quoteDao(),
            database.collectionDao(), database.searchHistoryDao(), database.drawingStrokeDao())
        var repo = repository()
        val bookId = repo.insertBook(Book(title = "Prueba aislada de tinta", filePath = file.path, format = "PDF"))
        val preferences = ReaderPreferences(context).apply { updateTheme(ReaderColorTheme.DAY) }
        val dark = mutableStateOf(false)
        var store = ViewModelStore()
        lateinit var model: ReaderViewModel
        lateinit var activity: ReaderInkTestActivity
        val scenario = ActivityScenario.launch(ReaderInkTestActivity::class.java)
        var restoreDisplay: String? = null
        fun mount() {
            scenario.onActivity {
                activity = it
                model = ReaderViewModel(bookId, repo, preferences, EngineManager(context), context)
                store.put("ink", model)
                it.setContent {
                    OpenReadEraTheme(appThemeMode = if (dark.value) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                        ReaderScreen(model, onBack = {}, onOpenDocumentDetails = {})
                    }
                }
            }
            awaitCondition {
                var ready = false
                main { ready = overlays(activity.window.decorView).firstOrNull()?.width?.let { it > 0 } == true }
                ready && model.uiState.value.currentPageBitmap != null
            }
        }
        fun currentOverlay(index: Int = 0): ReaderInkView {
            waitForFrame(activity.window.decorView)
            var result: ReaderInkView? = null
            awaitCondition {
                main { result = overlays(activity.window.decorView).getOrNull(index) }
                result?.let { it.isAttachedToWindow && it.isLaidOut && it.width > 0 && it.height > 0 } == true
            }
            instrumentation.waitForIdleSync()
            return checkNotNull(result)
        }
        fun openMenu(stylus: Boolean = false) {
            currentOverlay()
            if (model.uiState.value.writingMode) {
                node("Pluma")
                return
            }
            if (!model.uiState.value.isControlsVisible) {
                fingerTap(currentOverlay())
                awaitCondition { model.uiState.value.isControlsVisible }
            }
            if (activity.resources.configuration.screenWidthDp < 600) tapNode("Más opciones", stylus)
            tapNode("Herramientas de escritura", stylus)
            node("Pluma")
        }
        fun menuAction(label: String, stylus: Boolean = false) {
            openMenu(stylus)
            tapNode(label, stylus)
            assertTrue("History actions retain writing mode", model.uiState.value.writingMode)
            node("Pluma")
        }
        fun chooseTool(label: String, tool: DrawingTool, stylus: Boolean = false) {
            openMenu(stylus)
            tapNode(label, stylus)
            awaitCondition { model.uiState.value.currentDrawingTool == tool }
            assertNotNull("Tool selection retains menu", findNode("Pluma"))
            assertTrue(model.uiState.value.writingMode)
        }
        try {
            mount()
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            openMenu()
            main { model.setDrawingTool(DrawingTool.PEN); model.setDrawingColor("#2563EB"); model.setDrawingStrokeWidth(6f) }
            instrumentation.waitForIdleSync()
            val firstOverlay = currentOverlay()
            val nativeFirst = finishedInk(firstOverlay)
            assertEquals(600f / 850f, model.getPageAspectRatio(0), 0.00001f)
            stroke(firstOverlay, 0.2f, 0.4f, 0.7f, 0.48f, duringStroke = {
                val originalBitmap = model.uiState.value.currentPageBitmap
                main { model.setZoomScale(1.5f) }
                awaitCondition { model.uiState.value.currentPageBitmap !== originalBitmap }
                assertSame("Resolution upgrade must not cancel native ink", firstOverlay, currentOverlay())
                assertEquals(600f / 850f, model.getPageAspectRatio(0), 0.00001f)
            })
            awaitCondition { model.uiState.value.strokes.size == 1 }
            val first = model.uiState.value.strokes.single()
            assertTrue(first.pointsJson.startsWith("v4|"))
            val points = first.parseData().points
            assertEquals(0.2f, points.first().x, 0.001f)
            assertEquals(0.4f, points.first().y, 0.001f)
            assertEquals(0.7f, points.last().x, 0.001f)
            assertEquals(0.9f, points.last().pressure, 0.001f)
            assertTrue(checkNotNull(points.last().elapsedTimeMillis) > 0L)
            awaitCondition { nativeFirst.get() != null }
            val nativeInputs = checkNotNull(nativeFirst.get()).inputs
            assertEquals(points.size, nativeInputs.size)
            for (index in points.indices) {
                assertEquals(points[index].x * 600f, nativeInputs[index].x, 0.001f)
                assertEquals(points[index].y * 850f, nativeInputs[index].y, 0.001f)
                assertEquals(points[index].pressure, nativeInputs[index].pressure, 0.001f)
                assertEquals(points[index].elapsedTimeMillis, nativeInputs[index].elapsedTimeMillis)
            }
            // Production live meshes, genuine pressure samples, same color and useful tool defaults.
            val pressureMeshes = mutableMapOf<Pair<DrawingTool, Float>, androidx.ink.strokes.Stroke>()
            for ((row, pair) in listOf(DrawingTool.PEN to 0.2f, DrawingTool.PEN to 1f,
                DrawingTool.MARKER to 0.2f, DrawingTool.MARKER to 1f).withIndex()) {
                main { model.setDrawingTool(pair.first) }
                instrumentation.waitForIdleSync()
                val view = currentOverlay()
                val captured = finishedInk(view)
                stroke(view, 0.15f, 0.53f + row * 0.07f, 0.75f, 0.53f + row * 0.07f,
                    constantPressure = pair.second)
                awaitCondition { captured.get() != null && model.uiState.value.strokes.size == row + 2 }
                pressureMeshes[pair] = checkNotNull(captured.get())
                assertTrue(checkNotNull(captured.get()).inputs.let { batch ->
                    (0 until batch.size).all { batch[it].pressure == pair.second }
                })
                assertNotNull("Outside stylus drawing retains deployed panel", findNode("Pluma"))
            }
            fun meshWidth(tool: DrawingTool, pressure: Float) =
                checkNotNull(pressureMeshes[tool to pressure]?.shape?.computeBoundingBox()).height
            assertTrue("Pen tessellated width responds to actual high pressure",
                meshWidth(DrawingTool.PEN, 1f) > meshWidth(DrawingTool.PEN, 0.2f) * 1.35f)
            assertEquals("Marker width ignores pressure", meshWidth(DrawingTool.MARKER, 0.2f),
                meshWidth(DrawingTool.MARKER, 1f), 0.02f)
            screenshot("ink-pressure-pen-marker-panel")
            instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                "Geometry widths pen low/full=${meshWidth(DrawingTool.PEN, 0.2f)}/${meshWidth(DrawingTool.PEN, 1f)} " +
                    "marker low/full=${meshWidth(DrawingTool.MARKER, 0.2f)}/${meshWidth(DrawingTool.MARKER, 1f)}\n") })
            val equalTimes = listOf(0L, 0L, 8L, 8L, 16L, 16L, 24L, 24L, 32L)
            val rawView = currentOverlay()
            val rawNative = finishedInk(rawView)
            stroke(rawView, 0.2f, 0.84f, 0.7f, 0.84f,
                constantPressure = 0.23456789f, sampleTimes = equalTimes)
            awaitCondition { model.uiState.value.strokes.size == 6 && rawNative.get() != null }
            val rawSaved = model.uiState.value.strokes.last()
            assertEquals(equalTimes, rawSaved.parseData().points.map { it.elapsedTimeMillis })
            assertTrue(rawSaved.parseData().points.all { it.pressure == 0.23456789f })
            awaitCondition { renderedInk(rawView).size == 6 }
            val rawLiveInputs = checkNotNull(rawNative.get()).inputs
            val rawReplayInputs = renderedInk(rawView).last().inputs
            assertEquals(rawLiveInputs.size, rawReplayInputs.size)
            for (index in 0 until rawLiveInputs.size) {
                assertEquals(rawLiveInputs[index].x, rawReplayInputs[index].x, 0f)
                assertEquals(rawLiveInputs[index].y, rawReplayInputs[index].y, 0f)
                assertEquals(rawLiveInputs[index].pressure, rawReplayInputs[index].pressure, 0f)
                assertEquals(rawLiveInputs[index].elapsedTimeMillis, rawReplayInputs[index].elapsedTimeMillis)
            }
            repeat(5) { removed ->
                main { model.undoLastStroke() }
                awaitCondition { model.uiState.value.strokes.size == 5 - removed }
            }
            main { model.setDrawingTool(DrawingTool.PEN); model.setDrawingStrokeWidth(6f) }
            instrumentation.waitForIdleSync()
            stroke(currentOverlay(), 0.2f, 0.55f, 0.7f, 0.6f, canceled = true)
            delay(150)
            assertEquals(1, model.uiState.value.strokes.size)
            stroke(currentOverlay(), 0.2f, 0.55f, 0.7f, 0.6f, palm = true)
            awaitCondition { model.uiState.value.strokes.size == 2 }
            val palmSafe = model.uiState.value.strokes.last().parseData().points
            assertEquals(0.2f, palmSafe.first().x, 0.001f)
            assertEquals(0.7f, palmSafe.last().x, 0.001f)
            assertTrue(palmSafe.all { it.x < 0.8f })
            menuAction("Deshacer trazo")
            awaitCondition { model.uiState.value.strokes.size == 1 }

            chooseTool("Resaltar texto", DrawingTool.HIGHLIGHTER, stylus = true)
            awaitCondition { model.uiState.value.pageText[0]?.isLoading == false }
            assertEquals(20f, model.uiState.value.currentStrokeWidth, 0f)
            main { model.setDrawingColor("#FACC15") }
            instrumentation.waitForIdleSync()
            stroke(currentOverlay(), 0.12f, 0.265f, 0.8f, 0.265f)
            awaitCondition { model.uiState.value.strokes.size == 2 }
            assertTrue(model.uiState.value.strokes.single { it.toolType == StrokeToolType.HIGHLIGHTER }.parseData().isRectangularHighlight)
            screenshot("ink-pen-highlighter")

            menuAction("Deshacer trazo", stylus = true)
            awaitCondition { model.uiState.value.strokes.size == 1 }
            menuAction("Rehacer trazo")
            awaitCondition { model.uiState.value.strokes.size == 2 }
            stroke(currentOverlay(), 0.4f, 0.35f, 0.4f, 0.52f, MotionEvent.TOOL_TYPE_ERASER)
            awaitCondition { model.uiState.value.strokes.size == 1 }
            assertEquals(StrokeToolType.HIGHLIGHTER, model.uiState.value.strokes.single().toolType)
            menuAction("Rehacer trazo", stylus = true)
            awaitCondition { model.uiState.value.strokes.size == 2 }
            screenshot("ink-erase-redo")

            // UP persistence must not depend on Ink's asynchronous visual handoff or page teardown.
            chooseTool("Pluma", DrawingTool.PEN)
            assertEquals(4f, model.uiState.value.currentStrokeWidth, 0f)
            main { model.setDrawingColor("#DC2626") }
            instrumentation.waitForIdleSync()
            stroke(currentOverlay(), 0.25f, 0.7f, 0.75f, 0.7f)
            main { model.goToPage(1) }
            awaitCondition { model.uiState.value.currentPage == 1 && model.uiState.value.strokes.size == 3 }
            assertTrue(model.uiState.value.strokes.all { it.page == 0 })
            main { model.goToPage(0); model.updateViewMode(ReaderViewMode.DOUBLE_PAGE) }
            awaitCondition {
                var count = 0
                main { count = overlays(activity.window.decorView).size }
                count == 2 && model.uiState.value.secondPageBitmap != null
            }
            val secondOverlay = currentOverlay(1)
            val nativeSecond = finishedInk(secondOverlay)
            stroke(secondOverlay, 0.3f, 0.5f, 0.6f, 0.6f)
            awaitCondition { model.uiState.value.strokes.size == 4 }
            assertEquals(1, model.uiState.value.strokes.last().page)
            awaitCondition { nativeSecond.get() != null }
            assertTrue(checkNotNull(nativeSecond.get()).inputs.size >= 9)
            screenshot("ink-double-page")
            val leftStrokes = model.uiState.value.strokes.filter { it.page == 0 }
            assertEquals(1, model.uiState.value.activeInkPage)
            assertTrue(model.uiState.value.canUndo)
            menuAction("Deshacer trazo", stylus = true)
            awaitCondition { model.uiState.value.strokes.size == 3 && !model.uiState.value.canUndo }
            assertEquals(leftStrokes, model.uiState.value.strokes)
            screenshot("ink-right-page-undone")
            assertTrue(renderedInk(currentOverlay(1)).isEmpty())
            openMenu()
            assertEquals(1, model.uiState.value.activeInkPage)
            assertFalse(model.uiState.value.canUndo)
            assertTrue(model.uiState.value.canRedo)
            assertFalse(node("Deshacer trazo").isEnabled)
            assertFalse(node("Borrar trazos de la página").isEnabled)
            assertTrue(node("Rehacer trazo").isEnabled)
            tapNode("Rehacer trazo")
            awaitCondition { model.uiState.value.strokes.size == 4 }
            tapNode("Borrar trazos de la página")
            node("¿Borrar trazos?")
            tapNode("Cancelar", stylus = true)
            assertEquals(4, repo.getStrokesForBook(bookId).first().size)
            assertEquals(1, model.uiState.value.activeInkPage)
            tapNode("Borrar trazos de la página", stylus = true)
            node("Se borrarán los trazos de la página activa.")
            tapNode("Borrar")
            awaitCondition { model.uiState.value.strokes.size == 3 }
            assertEquals(leftStrokes, model.uiState.value.strokes)
            assertTrue(renderedInk(currentOverlay(1)).isEmpty())
            screenshot("ink-right-page-cleared")
            menuAction("Rehacer trazo")
            awaitCondition { model.uiState.value.strokes.size == 4 }
            main { model.updateViewMode(ReaderViewMode.VERTICAL_SCROLL) }
            awaitCondition {
                var count = 0
                main { count = overlays(activity.window.decorView).size }
                count == 1
            }
            // The full scroll page is taller than the display: draw within its visible upper part.
            stroke(currentOverlay(), 0.25f, 0.12f, 0.6f, 0.2f)
            awaitCondition { model.uiState.value.strokes.size == 5 }
            menuAction("Deshacer trazo")
            awaitCondition { model.uiState.value.strokes.size == 4 }
            menuAction("Rehacer trazo", stylus = true)
            awaitCondition { model.uiState.value.strokes.size == 5 }
            main { model.updateViewMode(ReaderViewMode.PAGED) }
            chooseTool("Rotulador", DrawingTool.MARKER, stylus = true)
            assertEquals(12f, model.uiState.value.currentStrokeWidth, 0f)
            val nativeMarker = finishedInk(currentOverlay())
            stroke(currentOverlay(), 0.15f, 0.77f, 0.75f, 0.77f)
            awaitCondition { model.uiState.value.strokes.size == 6 && nativeMarker.get() != null }
            val marker = model.uiState.value.strokes.single { it.toolType == StrokeToolType.MARKER }
            val liveMarker = checkNotNull(nativeMarker.get())
            assertEquals(androidx.ink.brush.StockBrushes.marker(androidx.ink.brush.StockBrushes.MarkerVersion.V1),
                liveMarker.brush.family)
            assertEquals(255, liveMarker.brush.colorIntArgb ushr 24)
            chooseTool("Resaltador libre", DrawingTool.FREE_HIGHLIGHTER)
            assertEquals(20f, model.uiState.value.currentStrokeWidth, 0f)
            val nativeHighlight = finishedInk(currentOverlay())
            stroke(currentOverlay(), 0.15f, 0.84f, 0.75f, 0.84f)
            awaitCondition { model.uiState.value.strokes.size == 7 && nativeHighlight.get() != null }
            val freeHighlight = model.uiState.value.strokes.single { it.toolType == StrokeToolType.FREE_HIGHLIGHTER }
            val liveHighlight = checkNotNull(nativeHighlight.get())
            assertEquals(androidx.ink.brush.StockBrushes.highlighter(androidx.ink.brush.SelfOverlap.DISCARD,
                androidx.ink.brush.StockBrushes.HighlighterVersion.V1), liveHighlight.brush.family)
            assertEquals(97, liveHighlight.brush.colorIntArgb ushr 24)
            assertFalse(freeHighlight.parseData().isRectangularHighlight)
            assertNotEquals(liveMarker.brush.family, liveHighlight.brush.family)
            chooseTool("Goma", DrawingTool.ERASER, stylus = true)
            stroke(currentOverlay(), 0.45f, 0.84f, 0.45f, 0.84f)
            awaitCondition { model.uiState.value.strokes.size == 6 }
            assertFalse(model.uiState.value.strokes.any { it.toolType == StrokeToolType.FREE_HIGHLIGHTER })
            menuAction("Rehacer trazo")
            awaitCondition { model.uiState.value.strokes.size == 7 }
            screenshot("ink-five-tools")
            val beforeReopen = model.uiState.value.strokes.sortedBy { it.id }
            main { activity.setContent {}; store.clear() }
            delay(600)
            database.close()
            database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
            repo = repository()
            store = ViewModelStore()
            mount()
            awaitCondition { model.uiState.value.strokes.size == 7 }
            assertEquals(DrawingTool.ERASER, model.uiState.value.currentDrawingTool)
            assertEquals(beforeReopen, model.uiState.value.strokes.sortedBy { it.id })
            main { model.updateViewMode(ReaderViewMode.PAGED); model.goToPage(0) }
            instrumentation.waitForIdleSync()
            delay(250)
            screenshot("ink-reopened")
            val reopened = renderedInk(currentOverlay())
            for ((record, live) in listOf(marker to liveMarker, freeHighlight to liveHighlight)) {
                val replay = reopened.single { it.brush.family == live.brush.family }
                assertEquals(live.brush, replay.brush)
                assertEquals(record.parseData().points.size, replay.inputs.size)
                for (index in 0 until replay.inputs.size) {
                    assertEquals(live.inputs[index].x, replay.inputs[index].x, 0.001f)
                    assertEquals(live.inputs[index].y, replay.inputs[index].y, 0.001f)
                    assertEquals(live.inputs[index].pressure, replay.inputs[index].pressure, 0f)
                    assertEquals(live.inputs[index].elapsedTimeMillis, replay.inputs[index].elapsedTimeMillis)
                }
            }
            chooseTool("Pluma", DrawingTool.PEN)
            openMenu()
            screenshot("ink-menu-tablet-light")
            dismissMenu()
            main { dark.value = true }
            openMenu(stylus = true)
            screenshot("ink-menu-tablet-dark")
            dismissMenu()
            assertTrue(instrumentation.uiAutomation.setRotation(android.view.Surface.ROTATION_90))
            awaitCondition { activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            openMenu()
            screenshot("ink-menu-landscape-dark")
            dismissMenu()
            assertTrue(instrumentation.uiAutomation.setRotation(android.view.Surface.ROTATION_0))
            awaitCondition { activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            openMenu()
            screenshot("ink-menu-portrait-dark")
            dismissMenu()
            val originalSize = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("wm size")).bufferedReader().use { it.readText() }
            val originalOverride = originalSize.lineSequence().firstOrNull { it.startsWith("Override size: ") }
                ?.substringAfter(": ")?.trim()
            restoreDisplay = if (originalOverride == null) "wm size reset" else "wm size $originalOverride"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("wm size 760x1600")).use { it.readBytes() }
            awaitCondition { activity.resources.configuration.screenWidthDp < 600 }
            openMenu()
            screenshot("ink-menu-phone-dark")
            dismissMenu()
            main { dark.value = false }
            openMenu(stylus = true)
            screenshot("ink-menu-phone-light")
            val outside = IntArray(2)
            main {
                outside[0] = activity.window.decorView.width - 32
                outside[1] = activity.window.decorView.height - 300
            }
            val outsideTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(outsideTime, SystemClock.uptimeMillis(), action,
                    outside[0].toFloat(), outside[1].toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                event.recycle()
            }
            assertNotNull("Outside drawing/navigation never dismisses the panel", findNode("Pluma"))
            assertTrue(model.uiState.value.writingMode)
            dismissMenu()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(checkNotNull(restoreDisplay))).use { it.readBytes() }
            restoreDisplay = null
            awaitCondition { activity.resources.configuration.screenWidthDp >= 600 }
            openMenu()
            // Hold the UI thread across save + removal so no Compose frame can observe
            // the saved row. The real Ink overlay must retire pending input explicitly.
            val handoffView = currentOverlay()
            val handoffPage = model.uiState.value.currentPage
            val pageStrokes = model.strokesForPage(handoffPage)
            assertTrue("Handoff page has persisted strokes", pageStrokes.isNotEmpty())
            val retainedStrokes = model.uiState.value.strokes.filterNot { it in pageStrokes }.sortedBy { it.id }
            val pendingField = ReaderInkView::class.java.getDeclaredField("pending").apply { isAccessible = true }
            val mutationField = ReaderViewModel::class.java.getDeclaredField("lastDrawingMutation").apply { isAccessible = true }
            for (clear in listOf(false, true)) {
                main {
                    val rect = PageDrawingGeometry.fitRect(handoffView.width.toFloat(), handoffView.height.toFloat(), 600f / 850f)
                    val time = SystemClock.uptimeMillis()
                    for ((index, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).withIndex()) {
                        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
                        val coordinates = MotionEvent.PointerCoords().apply {
                            x = rect.left + rect.width * (0.2f + index * 0.2f)
                            y = rect.top + rect.height * 0.65f; pressure = 0.7f
                        }
                        val event = MotionEvent.obtain(time, time + index * 12L, action, 1,
                            arrayOf(pointer), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                        assertTrue(handoffView.dispatchTouchEvent(event))
                        event.recycle()
                    }
                    assertTrue((pendingField.get(handoffView) as Map<*, *>).isNotEmpty())
                    if (clear) model.clearPageStrokes(handoffPage) else model.undoLastStroke(handoffPage)
                    runBlocking { withTimeout(10_000) { (mutationField.get(model) as kotlinx.coroutines.Job).join() } }
                    val removed = model.strokesForRemovedPage(handoffPage)
                    assertTrue("Removal is retained until the overlay acknowledges it", removed.isNotEmpty())
                    handoffView.configure(handoffPage, model.strokesForPage(handoffPage), DrawingTool.PEN,
                        model.uiState.value.currentStrokeColorHex, model.uiState.value.currentStrokeWidth,
                        model::onStrokeFinished, model::deleteStroke, model.getPageAspectRatio(handoffPage),
                        model.getPageTextRange(handoffPage), model.uiState.value.pageText[handoffPage]?.layout,
                        model::setActiveInkPage, removed, model::acknowledgeRemovedInkStrokes, true, 1f)
                    assertTrue("No pending ghost without a saved frame", (pendingField.get(handoffView) as Map<*, *>).isEmpty())
                }
                instrumentation.waitForIdleSync()
                delay(250)
                main { assertTrue((pendingField.get(handoffView) as Map<*, *>).isEmpty()) }
            }
            assertTrue(model.strokesForPage(handoffPage).isEmpty())
            assertEquals(retainedStrokes, model.uiState.value.strokes.sortedBy { it.id })
            assertEquals(retainedStrokes, repo.getStrokesForBook(bookId).first().sortedBy { it.id })
            assertTrue(renderedInk(currentOverlay()).isEmpty())
            screenshot("ink-rapid-clear-no-ghost")
            val controlsWereVisible = model.uiState.value.isControlsVisible
            fingerTap(currentOverlay())
            awaitCondition { model.uiState.value.isControlsVisible != controlsWereVisible }
            screenshot("ink-reader-controls")
            assertNotNull("Writing panel outlives hidden reader chrome", findNode("Pluma"))
            instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                "Ink reader: actual finger/stylus toolbar undo/redo, clear cancel/confirm, right-page isolation, scroll history, five tools, brush/sample replay, durable Room reopen and deterministic no-saved-frame undo/clear passed; isolated fixture cleaned afterward.\n") })
        } catch (failure: Throwable) {
            saveAccessibilityEvidence()
            screenshot("ink-menu-failure")
            throw failure
        } finally {
            restoreDisplay?.let { command ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
            }
            var overlayCount = 0
            main { overlayCount = overlays(activity.window.decorView).size }
            val finalState = model.uiState.value
            instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                "Ink final state: strokes=${finalState.strokes.size} page=${finalState.currentPage} " +
                    "mode=${finalState.settings.viewMode} overlays=$overlayCount " +
                    "second=${finalState.secondPageBitmap != null} error=${finalState.error}\n") })
            main { activity.setContent {}; store.clear() }
            scenario.close()
            instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
            delay(600)
            database.close()
            target.deleteDatabase(databaseName)
            file.delete()
            synchronized(names) { names.forEach(target::deleteSharedPreferences) }
        }
    }
    @Test
    fun floatingToolbarRendersWithoutVisibleTextAndOverlayDoesNotResizeViewport() = runBlocking {
        val target = instrumentation.targetContext
        val prefix = "floating_bar_${System.nanoTime()}_"
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val isolated = prefix + name
                synchronized(names) { names.add(isolated) }
                return super.getSharedPreferences(isolated, mode)
            }
        }
        context.getSharedPreferences("google_sync_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("explicitly_signed_out", true).commit()
        context.getSharedPreferences("drive_sync_data", Context.MODE_PRIVATE).edit()
            .putBoolean("auto_sync_enabled", false).commit()
        val file = fixture(context)
        val databaseName = prefix + "reader.db"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        fun repository() = BookRepository(database.bookDao(), database.bookmarkDao(), database.quoteDao(),
            database.collectionDao(), database.searchHistoryDao(), database.drawingStrokeDao())
        val repo = repository()
        val bookId = repo.insertBook(Book(title = "Prueba barra flotante", filePath = file.path, format = "PDF"))
        val preferences = ReaderPreferences(context).apply { updateTheme(ReaderColorTheme.DAY) }
        val dark = mutableStateOf(false)
        val store = ViewModelStore()
        lateinit var model: ReaderViewModel
        lateinit var activity: ReaderInkTestActivity
        val scenario = ActivityScenario.launch(ReaderInkTestActivity::class.java)
        var restoreDisplay: String? = null
        fun mount() {
            scenario.onActivity {
                activity = it
                model = ReaderViewModel(bookId, repo, preferences, EngineManager(context), context)
                store.put("ink", model)
                it.setContent {
                    OpenReadEraTheme(appThemeMode = if (dark.value) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                        ReaderScreen(model, onBack = {}, onOpenDocumentDetails = {})
                    }
                }
            }
            awaitCondition {
                var ready = false
                main { ready = overlays(activity.window.decorView).isNotEmpty() }
                ready && model.uiState.value.currentPageBitmap != null
            }
        }
        fun currentOverlay(): ReaderInkView {
            waitForFrame(activity.window.decorView)
            var result: ReaderInkView? = null
            awaitCondition {
                main { result = overlays(activity.window.decorView).firstOrNull() }
                result?.let { it.isAttachedToWindow && it.isLaidOut && it.width > 0 && it.height > 0 } == true
            }
            instrumentation.waitForIdleSync()
            return checkNotNull(result)
        }
        fun openMenu(stylus: Boolean = false) {
            currentOverlay()
            if (model.uiState.value.writingMode) {
                node("Pluma")
                return
            }
            if (!model.uiState.value.isControlsVisible) {
                fingerTap(currentOverlay())
                awaitCondition { model.uiState.value.isControlsVisible }
            }
            if (activity.resources.configuration.screenWidthDp < 600) tapNode("Más opciones", stylus)
            tapNode("Herramientas de escritura", stylus)
            node("Pluma")
        }
        fun dismissMenu() {
            tapNode("Cerrar escritura")
            awaitCondition { findNode("Pluma") == null }
        }
        try {
            mount()
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val overlayBefore = currentOverlay()
            val initialWidth = overlayBefore.width
            assertTrue("Initial overlay width must be positive", initialWidth > 0)

            // Open writing mode
            openMenu()
            assertTrue(model.uiState.value.writingMode)

            // 1. Viewport geometry invariant: viewport/overlay width must be 100% identical (no writingEndPadding side reservation)
            val overlayDuringWriting = currentOverlay()
            assertEquals("Document viewport width remains 100% identical when writing mode is active",
                initialWidth, overlayDuringWriting.width)

            // 2. Floating bar bounds: overlay is positioned within document window bounds
            val plumaNode = node("Pluma")
            val plumaBounds = Rect().also(plumaNode::getBoundsInScreen)
            val decorBounds = Rect()
            main { activity.window.decorView.getGlobalVisibleRect(decorBounds) }
            assertTrue("Floating bar overlay bounds are within document decor bounds", decorBounds.contains(plumaBounds))

            // 3. UI no-text regression: all controls have accessible contentDescription, but ZERO visible text
            val toolLabels = listOf("Pluma", "Rotulador", "Resaltador libre", "Resaltar texto", "Goma")
            for (toolLabel in toolLabels) {
                val n = node(toolLabel)
                assertEquals(toolLabel, n.contentDescription?.toString())
                assertTrue("Tool '$toolLabel' must have NO visible text", n.text.isNullOrEmpty())
            }

            val widthLabels = listOf("Grosor Fino", "Grosor Medio", "Grosor Grueso")
            for (widthLabel in widthLabels) {
                val n = node(widthLabel)
                assertEquals(widthLabel, n.contentDescription?.toString())
                assertTrue("Width sample '$widthLabel' must have NO visible text", n.text.isNullOrEmpty())
            }

            val colorLabels = listOf("Color Amarillo", "Color Verde", "Color Azul", "Color Coral", "Color Naranja", "Color Negro")
            for (colorLabel in colorLabels) {
                val n = node(colorLabel)
                assertEquals(colorLabel, n.contentDescription?.toString())
                assertTrue("Color swatch '$colorLabel' must have NO visible text", n.text.isNullOrEmpty())
            }

            val actionLabels = listOf("Deshacer trazo", "Rehacer trazo", "Borrar trazos de la página", "Cerrar escritura")
            for (actionLabel in actionLabels) {
                val n = node(actionLabel)
                assertEquals(actionLabel, n.contentDescription?.toString())
                assertTrue("Action '$actionLabel' must have NO visible text", n.text.isNullOrEmpty())
            }

            // Verify no persistent text nodes in the bar (no headers or descriptions)
            assertNull("No title text node in bar", findNode("Herramientas de escritura")?.takeIf {
                it.text?.toString() == "Herramientas de escritura" && !it.isClickable
            })

            // 4. Interactive tool selection via UI
            tapNode("Rotulador")
            awaitCondition { model.uiState.value.currentDrawingTool == DrawingTool.MARKER }
            tapNode("Resaltador libre")
            awaitCondition { model.uiState.value.currentDrawingTool == DrawingTool.FREE_HIGHLIGHTER }
            tapNode("Resaltar texto")
            awaitCondition { model.uiState.value.currentDrawingTool == DrawingTool.HIGHLIGHTER }
            tapNode("Goma")
            awaitCondition { model.uiState.value.currentDrawingTool == DrawingTool.ERASER }

            // Contextual hiding: eraser mode shows NO width or color swatches
            assertNull("Eraser hides stroke width swatches", findNode("Grosor Fino"))
            assertNull("Eraser hides color swatches", findNode("Color Amarillo"))

            tapNode("Pluma")
            awaitCondition { model.uiState.value.currentDrawingTool == DrawingTool.PEN }
            assertNotNull("Pen restores stroke width swatches", findNode("Grosor Fino"))
            assertNotNull("Pen restores color swatches", findNode("Color Amarillo"))

            // 5. Interactive stroke width selection via UI
            tapNode("Grosor Medio")
            awaitCondition { model.uiState.value.currentStrokeWidth == 4f }
            tapNode("Grosor Grueso")
            awaitCondition { model.uiState.value.currentStrokeWidth == 8f }
            tapNode("Grosor Fino")
            awaitCondition { model.uiState.value.currentStrokeWidth == 2f }

            // 6. Interactive color selection via UI
            tapNode("Color Coral")
            awaitCondition { model.uiState.value.currentStrokeColorHex.equals("#F87171", ignoreCase = true) }
            tapNode("Color Azul")
            awaitCondition { model.uiState.value.currentStrokeColorHex.equals("#60A5FA", ignoreCase = true) }

            // 7. Drawing outside floating bar creates a stroke without dismissing the bar
            val inkView = currentOverlay()
            stroke(inkView, 0.2f, 0.55f, 0.7f, 0.55f)
            awaitCondition { model.uiState.value.strokes.size == 1 }
            assertTrue("Writing mode retained after outside stroke", model.uiState.value.writingMode)
            assertNotNull("Floating bar retained after outside stroke", findNode("Pluma"))

            // 8. Undo and Redo actions via UI
            tapNode("Deshacer trazo")
            awaitCondition { model.uiState.value.strokes.isEmpty() }
            tapNode("Rehacer trazo")
            awaitCondition { model.uiState.value.strokes.size == 1 }

            // 9. Clear confirmation dialog guarded behavior
            tapNode("Borrar trazos de la página")
            node("¿Borrar trazos?")
            tapNode("Cancelar")
            assertEquals(1, model.uiState.value.strokes.size)
            tapNode("Borrar trazos de la página")
            node("¿Borrar trazos?")
            tapNode("Borrar")
            awaitCondition { model.uiState.value.strokes.isEmpty() }

            // 10. Close button closes writing mode
            tapNode("Cerrar escritura")
            awaitCondition { !model.uiState.value.writingMode }
            awaitCondition { findNode("Pluma") == null }

            // Viewport width remains identical after closing writing mode
            assertEquals("Viewport width remains identical when writing mode is closed",
                initialWidth, currentOverlay().width)

            // 11. Screenshots in light, dark, landscape, portrait, and narrow modes
            openMenu()
            screenshot("floating-toolbar-tablet-light")
            dismissMenu()

            main { dark.value = true }
            openMenu(stylus = true)
            screenshot("floating-toolbar-tablet-dark")
            dismissMenu()

            assertTrue(instrumentation.uiAutomation.setRotation(android.view.Surface.ROTATION_90))
            awaitCondition { activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            openMenu()
            screenshot("floating-toolbar-tablet-landscape")
            dismissMenu()

            assertTrue(instrumentation.uiAutomation.setRotation(android.view.Surface.ROTATION_0))
            awaitCondition { activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            openMenu()
            screenshot("floating-toolbar-tablet-portrait")
            dismissMenu()

            val originalSize = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("wm size")).bufferedReader().use { it.readText() }
            val originalOverride = originalSize.lineSequence().firstOrNull { it.startsWith("Override size: ") }
                ?.substringAfter(": ")?.trim()
            restoreDisplay = if (originalOverride == null) "wm size reset" else "wm size $originalOverride"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("wm size 760x1600")).use { it.readBytes() }
            awaitCondition { activity.resources.configuration.screenWidthDp < 600 }
            openMenu()
            screenshot("floating-toolbar-narrow")
            dismissMenu()

            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(checkNotNull(restoreDisplay))).use { it.readBytes() }
            restoreDisplay = null
            awaitCondition { activity.resources.configuration.screenWidthDp >= 600 }

            instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                "Floating ink bar smoke: no-text regression, viewport stability, tool/color/width selection, outside drawing, and screenshots passed.\n") })
        } catch (failure: Throwable) {
            saveAccessibilityEvidence()
            screenshot("floating-toolbar-failure")
            throw failure
        } finally {
            restoreDisplay?.let { command ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
            }
            main { activity.setContent {}; store.clear() }
            scenario.close()
            instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
            delay(600)
            database.close()
            target.deleteDatabase(databaseName)
            file.delete()
            synchronized(names) { names.forEach(target::deleteSharedPreferences) }
        }
    }
    @Test
    fun realReaderResolvesRasterPdfAndComicWordsAndRoutesWritingNavigation() = runBlocking {
        val target = instrumentation.targetContext
        val prefix = "reader_ocr_${System.nanoTime()}_"
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val isolated = prefix + name
                synchronized(names) { names.add(isolated) }
                return super.getSharedPreferences(isolated, mode)
            }
        }
        context.getSharedPreferences("google_sync_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("explicitly_signed_out", true).commit()
        context.getSharedPreferences("drive_sync_data", Context.MODE_PRIVATE).edit()
            .putBoolean("auto_sync_enabled", false).commit()
        val image = Bitmap.createBitmap(600, 850, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(image).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 28f }
            drawText("OCR LOCAL PRUEBA AISLADA", 40f, 120f, paint)
            drawText("Resaltar estas palabras solamente", 40f, 220f, paint)
        }
        val blank = Bitmap.createBitmap(600, 850, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val pdf = File.createTempFile("reader-raster-", ".pdf", context.cacheDir)
        PdfDocument().apply {
            for ((index, bitmap) in listOf(image, blank).withIndex()) {
                val page = startPage(PdfDocument.PageInfo.Builder(600, 850, index + 1).create())
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                finishPage(page)
            }
            pdf.outputStream().use(::writeTo)
            close()
        }
        val comic = File.createTempFile("reader-raster-", ".cbz", context.cacheDir)
        java.util.zip.ZipOutputStream(comic.outputStream()).use { zip ->
            for ((index, bitmap) in listOf(image, blank).withIndex()) {
                zip.putNextEntry(java.util.zip.ZipEntry("$index.png"))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
        }
        image.recycle()
        blank.recycle()
        val nativeFile = fixture(context)
        val databaseName = prefix + "reader.db"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        val repository = BookRepository(database.bookDao(), database.bookmarkDao(), database.quoteDao(),
            database.collectionDao(), database.searchHistoryDao(), database.drawingStrokeDao())
        val pdfId = repository.insertBook(Book(title = "OCR PDF aislado", filePath = pdf.path, format = "PDF"))
        val comicId = repository.insertBook(Book(title = "OCR CBZ aislado", filePath = comic.path, format = "CBZ"))
        val nativeId = repository.insertBook(Book(title = "Texto nativo aislado", filePath = nativeFile.path, format = "PDF"))
        val preferences = ReaderPreferences(context).apply { updateTheme(ReaderColorTheme.NIGHT) }
        val scenario = ActivityScenario.launch(ReaderInkTestActivity::class.java)
        var store = ViewModelStore()
        lateinit var model: ReaderViewModel
        lateinit var activity: ReaderInkTestActivity
        fun mount(id: Long) {
            scenario.onActivity {
                activity = it
                activity.setContent {}
                store.clear()
                store = ViewModelStore()
                model = ReaderViewModel(id, repository, preferences, EngineManager(context), context)
                store.put("reader", model)
                activity.setContent {
                    OpenReadEraTheme {
                        ReaderScreen(model, onBack = {}, onOpenDocumentDetails = {})
                    }
                }
            }
            awaitCondition { model.uiState.value.currentPageBitmap != null }
            if (model.uiState.value.currentPage != 0) {
                main { model.goToPage(0) }
                awaitCondition { model.uiState.value.currentPage == 0 && model.uiState.value.currentPageBitmap != null }
            }
        }
        fun view(): ReaderInkView {
            waitForFrame(activity.window.decorView)
            var value: ReaderInkView? = null
            awaitCondition {
                main { value = overlays(activity.window.decorView).firstOrNull() }
                value?.let { it.isLaidOut && it.width > 0 } == true
            }
            instrumentation.waitForIdleSync()
            return checkNotNull(value)
        }
        fun writing() {
            if (!model.uiState.value.isControlsVisible) main { model.toggleControls() }
            tapNode("Herramientas de escritura", stylus = true)
            awaitCondition { model.uiState.value.writingMode }
            node("Cerrar escritura")
            view()
        }
        fun rawGesture(x1: Float, y1: Float, x2: Float = x1, y2: Float = y1, stylus: Boolean = true,
            duringGesture: (() -> Unit)? = null) {
            val time = SystemClock.uptimeMillis()
            for (step in 0..4) {
                val pointer = MotionEvent.PointerProperties().apply {
                    id = 0; toolType = if (stylus) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER
                }
                val coords = MotionEvent.PointerCoords().apply {
                    x = x1 + (x2 - x1) * step / 4; y = y1 + (y2 - y1) * step / 4
                    pressure = 0.7f; size = 0.01f
                }
                val action = if (step == 0) MotionEvent.ACTION_DOWN else if (step == 4) MotionEvent.ACTION_UP
                    else MotionEvent.ACTION_MOVE
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, 1,
                    arrayOf(pointer), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                    if (stylus) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN, 0)
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                event.recycle()
                if (step == 2) duringGesture?.invoke()
                SystemClock.sleep(12)
            }
            instrumentation.waitForIdleSync()
        }
        val hardwareInputs = java.util.Collections.synchronizedList(
            mutableListOf<org.readera.openreadera.ui.reader.ReaderHardwareInput>())
        val hardwareObserver = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            org.readera.openreadera.ui.reader.ReaderEventBus.events.collect { hardwareInputs.add(it) }
        }
        fun hardwareKey(code: Int, isStylus: Boolean = false) {
            val before = hardwareInputs.size
            val time = SystemClock.uptimeMillis()
            for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
                val event = android.view.KeyEvent(time, SystemClock.uptimeMillis(), action, code, 0, 0,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    if (isStylus) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_KEYBOARD)
                if (action == android.view.KeyEvent.ACTION_DOWN) {
                    assertTrue(org.readera.openreadera.ui.reader.ReaderEventBus.dispatchKeyEvent(event))
                }
            }
            awaitCondition { hardwareInputs.size > before }
            assertEquals(isStylus || code == 308 || code == 309, hardwareInputs.last().isStylus)
        }
        fun pinch(centerX: Float, centerY: Float, factor: Float) {
            val time = SystemClock.uptimeMillis()
            fun send(action: Int, count: Int, radius: Float) {
                val pointers = Array(count) { i -> MotionEvent.PointerProperties().apply {
                    id = i; toolType = MotionEvent.TOOL_TYPE_FINGER
                } }
                val coordinates = Array(count) { i -> MotionEvent.PointerCoords().apply {
                    x = centerX + if (i == 0) -radius else radius
                    y = centerY; pressure = 1f; size = 0.01f
                } }
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, count,
                    pointers, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                event.recycle()
                SystemClock.sleep(16)
            }
            send(MotionEvent.ACTION_DOWN, 1, 120f)
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 120f)
            for (step in 1..8) send(MotionEvent.ACTION_MOVE, 2, 120f * (1f + (factor - 1f) * step / 8))
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 120f * factor)
            send(MotionEvent.ACTION_UP, 1, 120f * factor)
            instrumentation.waitForIdleSync()
        }
        fun displayedZoom(): Float? {
            fun visit(node: AccessibilityNodeInfo): Float? {
                val text = node.text?.toString().orEmpty()
                if (text.endsWith("%")) text.dropLast(1).toFloatOrNull()?.let { return it / 100f }
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it) }?.let { return it }
                return null
            }
            return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        }
        fun slider(): AccessibilityNodeInfo {
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) {
                if (node.rangeInfo != null) candidates.add(node)
                for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            }
            instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
            return checkNotNull(candidates.maxByOrNull { Rect().also(it::getBoundsInScreen).bottom })
        }
        fun ocrInitialized(value: ReaderViewModel): Boolean =
            (ReaderViewModel::class.java.getDeclaredField("ocrDelegate").apply { isAccessible = true }
                .get(value) as Lazy<*>).isInitialized()
        try {
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            // Cancel a real image-page request on a fast book switch; no recognizer substitutes.
            mount(pdfId)
            val abandoned = model
            main { model.requestPageText(setOf(0)); model.goToPage(1) }
            mount(nativeId)
            awaitCondition { model.uiState.value.pageText[0]?.isLoading == false }
            val nativeLayout = checkNotNull(model.uiState.value.pageText[0]?.layout)
            assertEquals(org.readera.openreadera.engine.PageTextSource.NATIVE, nativeLayout.source)
            assertTrue(nativeLayout.text.contains("Prueba aislada"))
            assertFalse("Native text/geometry must not initialize OCR", ocrInitialized(model))
            assertFalse(nativeLayout.text.contains("OCR LOCAL"))
            assertTrue(abandoned.uiState.value.pageText.values.none { it.layout?.text?.contains("OCR LOCAL") == true })

            for (id in listOf(pdfId, comicId)) {
                mount(id)
                writing()
                main { model.setDrawingTool(DrawingTool.HIGHLIGHTER) }
                awaitCondition { model.uiState.value.pageText[0]?.isLoading == false }
                val layout = checkNotNull(model.uiState.value.pageText[0]?.layout)
                assertEquals(org.readera.openreadera.engine.PageTextSource.OCR, layout.source)
                assertTrue("Real local model recognizes raster text", layout.text.contains("OCR LOCAL"))
                assertTrue(layout.words.size >= 7)
                val stable = layout
                main { model.setZoomScale(1.5f); model.requestPageText(setOf(0)) }
                assertSame("Zoom quality does not invalidate recognized text", stable, model.uiState.value.pageText[0]?.layout)
                val line = layout.words.filter { it.lineIndex == layout.words.first().lineIndex }
                val selected = line.drop(1).take(2)
                assertEquals(2, selected.size)
                val y = selected.first().bounds.centerY()
                stroke(view(), selected.first().bounds.centerX(), y, selected.last().bounds.centerX(), y)
                awaitCondition { model.uiState.value.strokes.size == 1 }
                val points = model.uiState.value.strokes.single().parseData().points
                assertTrue(model.uiState.value.strokes.single().parseData().isRectangularHighlight)
                assertEquals(2, points.size)
                assertEquals(selected.minOf { it.bounds.left }, points.first().x, 0.001f)
                assertEquals(selected.maxOf { it.bounds.right }, points.last().x, 0.001f)
                assertEquals(selected.minOf { it.bounds.top }, points.first().y, 0.001f)
                assertEquals(selected.maxOf { it.bounds.bottom }, points.last().y, 0.001f)
                if (id == pdfId) {
                    val unzoomedView = view()
                    val origin = IntArray(2)
                    main { unzoomedView.getLocationOnScreen(origin) }
                    val fitted = PageDrawingGeometry.fitRect(unzoomedView.width.toFloat(),
                        unzoomedView.height.toFloat(), 600f / 850f)
                    val cx = origin[0] + unzoomedView.width / 2f
                    val cy = origin[1] + unzoomedView.height / 2f
                    pinch(cx, cy, 1.6f)
                    awaitCondition { (displayedZoom() ?: 1f) > 1.3f }
                    val z = checkNotNull(displayedZoom())
                    val secondLine = layout.words.filter { it.lineIndex == layout.words.last().lineIndex }.drop(1).take(2)
                    assertEquals(2, secondLine.size)
                    fun zoomedX(word: org.readera.openreadera.engine.PageTextWord) =
                        cx + (origin[0] + fitted.left + word.bounds.centerX() * fitted.width - cx) * z
                    val zy = cy + (origin[1] + fitted.top + secondLine.first().bounds.centerY() * fitted.height - cy) * z
                    val zoomView = view()
                    var sampled: org.readera.openreadera.data.model.StrokePoint? = null
                    var sampleRect: InkRect? = null
                    rawGesture(zoomedX(secondLine.first()), zy, zoomedX(secondLine.last()), zy,
                        duringGesture = {
                            main {
                                val gesture = ReaderInkView::class.java.getDeclaredField("gesture").apply {
                                    isAccessible = true
                                }.get(zoomView)
                                sampleRect = gesture?.javaClass?.getDeclaredField("rect")?.apply {
                                    isAccessible = true
                                }?.get(gesture) as? InkRect
                                @Suppress("UNCHECKED_CAST")
                                val samples = gesture?.javaClass?.getDeclaredField("points")?.apply {
                                    isAccessible = true
                                }?.get(gesture) as? List<org.readera.openreadera.data.model.StrokePoint>
                                sampled = samples?.firstOrNull()
                            }
                        })
                    assertNotNull("Actual zoomed stylus input must reach the page ink view", sampled)
                    val mappedLocation = IntArray(2)
                    main { zoomView.getLocationOnScreen(mappedLocation) }
                    assertEquals("Zoom map before=${origin.toList()} size=${unzoomedView.width}x${unzoomedView.height} " +
                        "fit=$fitted center=$cx,$cy zoom=$z global=${zoomedX(secondLine.first())},$zy " +
                        "after=${mappedLocation.toList()} sample=$sampled rect=$sampleRect",
                        secondLine.first().bounds.centerX(), checkNotNull(sampled).x, 0.01f)
                    assertEquals(secondLine.first().bounds.centerY(), checkNotNull(sampled).y, 0.01f)
                    awaitCondition { model.uiState.value.strokes.size == 2 }
                    val zoomPoints = model.uiState.value.strokes.last().parseData().points
                    assertEquals(secondLine.minOf { it.bounds.left }, zoomPoints.first().x, 0.001f)
                    assertEquals(secondLine.maxOf { it.bounds.right }, zoomPoints.last().x, 0.001f)
                    assertEquals(secondLine.minOf { it.bounds.top }, zoomPoints.first().y, 0.001f)
                    assertEquals(secondLine.maxOf { it.bounds.bottom }, zoomPoints.last().y, 0.001f)
                    assertSame(layout, model.uiState.value.pageText[0]?.layout)
                    screenshot("reader-ocr-pinch-zoom-exact-word-highlight")
                    tapNode("1x", stylus = true)
                    awaitCondition { findNode("1x") == null }
                }
                screenshot(if (id == pdfId) "reader-real-ocr-pdf-word-highlight" else "reader-real-ocr-cbz-word-highlight")
                assertNotNull(findNode("Pluma"))
                // Actual sheet uses the same recognized text, with working selection/copy.
                tapNode("Remarcar texto", stylus = true)
                val paragraph = layout.text.lineSequence().first()
                tapNode(paragraph, stylus = true)
                tapNode("Copiar cita bibliográfica", stylus = true)
                var clipboardText = ""
                main {
                    clipboardText = (activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                }
                assertTrue("Copied citation contains actual OCR words", clipboardText.contains(paragraph))
                screenshot(if (id == pdfId) "reader-real-ocr-selectable-copy" else "reader-real-ocr-cbz-selectable-copy")
                tapNode("Cerrar")
                tapNode("Añadir cita o nota", stylus = true)
                node(layout.text.take(250).trim())
                tapNode("Cancelar")

                // Side keys, stylus controls/slider and blank margins do not change page.
                for (key in listOf(308, 309)) hardwareKey(key, isStylus = true)
                tapNode("Página siguiente", stylus = true)
                val sliderBounds = Rect().also(slider()::getBoundsInScreen)
                rawGesture(sliderBounds.left + sliderBounds.width() * 0.15f, sliderBounds.exactCenterY(),
                    sliderBounds.right - 2f, sliderBounds.exactCenterY())
                val location = IntArray(2)
                val overlay = view()
                main { overlay.getLocationOnScreen(location) }
                rawGesture(location[0] + 3f, location[1] + overlay.height * 0.55f,
                    location[0] + 3f, location[1] + overlay.height * 0.35f)
                delay(150)
                assertEquals(0, model.uiState.value.currentPage)
                assertTrue(model.uiState.value.writingMode)
                if (id == pdfId) {
                    assertTrue(instrumentation.uiAutomation.setRotation(android.view.Surface.ROTATION_90))
                    awaitCondition { activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                    val pagedView = view()
                    main { model.updateViewMode(ReaderViewMode.VERTICAL_SCROLL) }
                    awaitCondition {
                        var newOverlay: ReaderInkView? = null
                        main { newOverlay = overlays(activity.window.decorView).firstOrNull() }
                        newOverlay != null && newOverlay !== pagedView && checkNotNull(newOverlay).isLaidOut
                    }
                    val scrollView = view()
                    val before = IntArray(2)
                    main { scrollView.getLocationOnScreen(before) }
                    val marginX = before[0] + scrollView.width + 8f
                    val startY = before[1] + activity.window.decorView.height * 0.45f
                    rawGesture(marginX, startY, marginX, startY - 160f)
                    val afterStylus = IntArray(2)
                    main { scrollView.getLocationOnScreen(afterStylus) }
                    assertEquals("Stylus on scroll-page blank margin does not scroll", before[1], afterStylus[1])
                    rawGesture(marginX, startY, marginX, startY - 160f, stylus = false)
                    val afterFinger = IntArray(2)
                    awaitCondition {
                        main { scrollView.getLocationOnScreen(afterFinger) }
                        afterFinger[1] < before[1] - 30
                    }
                    assertTrue(model.uiState.value.writingMode)
                    screenshot("reader-ocr-scroll-finger-not-stylus")
                    main { model.updateViewMode(ReaderViewMode.PAGED) }
                    assertTrue(instrumentation.uiAutomation.setRotation(android.view.Surface.ROTATION_0))
                    awaitCondition { activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
                    view()
                }
                tapNode("Página siguiente")
                awaitCondition { model.uiState.value.currentPage == 1 && model.uiState.value.pageText[1]?.isLoading == false }
                assertEquals(setOf(1), model.uiState.value.pageText.keys)
                assertTrue(checkNotNull(model.uiState.value.pageText[1]?.layout).text.isBlank())
                assertTrue(checkNotNull(model.uiState.value.pageText[1]?.layout).words.isEmpty())
                stroke(view(), 0.15f, 0.3f, 0.75f, 0.4f)
                delay(200)
                assertEquals("Blank highlighter never commits a rectangle", if (id == pdfId) 2 else 1, model.uiState.value.strokes.size)
                assertNotNull(findNode("No se detectó texto en esta página"))
                screenshot(if (id == pdfId) "reader-ocr-blank-no-fallback" else "reader-ocr-cbz-blank-no-fallback")
                for (key in listOf(308, 309)) hardwareKey(key, isStylus = true)
                delay(100)
                assertEquals(1, model.uiState.value.currentPage)
                // Ordinary hardware route remains available while writing.
                hardwareKey(android.view.KeyEvent.KEYCODE_PAGE_UP, isStylus = true)
                assertEquals(1, model.uiState.value.currentPage)
                hardwareKey(android.view.KeyEvent.KEYCODE_PAGE_UP)
                awaitCondition { model.uiState.value.currentPage == 0 }
                assertTrue(model.uiState.value.writingMode)
                hardwareKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                awaitCondition { model.uiState.value.currentPage == 1 }
                hardwareKey(android.view.KeyEvent.KEYCODE_PAGE_UP)
                awaitCondition { model.uiState.value.currentPage == 0 }
                dismissMenu()
                hardwareKey(309, isStylus = true)
                awaitCondition { model.uiState.value.currentPage == 1 }
                val count = model.uiState.value.strokes.size
                stroke(view(), 0.2f, 0.5f, 0.7f, 0.5f)
                delay(150)
                assertEquals("Writing off does not ink silently", count, model.uiState.value.strokes.size)
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                "Real reader native-first/no-OCR, image PDF+CBZ MLKit words/copy/quote, exact word highlight, " +
                    "blank no-fallback, canceled book/page work, persistent panel and typed stylus navigation passed.\n") })
        } catch (failure: Throwable) {
            saveAccessibilityEvidence()
            screenshot("reader-ocr-runtime-failure")
            throw failure
        } finally {
            hardwareObserver.cancel()
            main { activity.setContent {}; store.clear() }
            scenario.close()
            instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
            delay(600)
            database.close()
            target.deleteDatabase(databaseName)
            listOf(pdf, comic, nativeFile).forEach(File::delete)
            synchronized(names) { names.forEach(target::deleteSharedPreferences) }
        }
    }

}
