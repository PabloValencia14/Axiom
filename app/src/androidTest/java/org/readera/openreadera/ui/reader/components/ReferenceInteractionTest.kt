package org.readera.openreadera.ui.reader.components

import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readera.openreadera.engine.*
import org.readera.openreadera.papers.*
import org.readera.openreadera.ui.theme.OpenReadEraTheme
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReferenceInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @org.junit.Before fun enableWindowInspection() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    private fun findNode(label: String): android.view.accessibility.AccessibilityNodeInfo? {
        fun search(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if (node.text?.toString()?.contains(label) == true || node.contentDescription?.toString()?.contains(label) == true) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { search(it)?.let { value -> return value } }
            return null
        }
        return (instrumentation.uiAutomation.windows.mapNotNull { it.root } + listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow))
            .filter { it.packageName?.toString() == instrumentation.targetContext.packageName }.firstNotNullOfOrNull(::search)
    }


    @Test fun realReaderTapOpensReferenceAndJumpsToItsBibliographyPage() = kotlinx.coroutines.runBlocking {
        val targetContext = instrumentation.targetContext
        val names = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val prefix = "references-qa-${java.util.UUID.randomUUID()}-"
        val context = object : android.content.ContextWrapper(targetContext) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                names += prefix + name
                return targetContext.getSharedPreferences(prefix + name, mode)
            }
        }
        val pdf = File(context.cacheDir, "$prefix.pdf")
        val document = android.graphics.pdf.PdfDocument()
        try {
            val paint = android.graphics.Paint().apply { textSize = 16f }
            for ((number, lines) in listOf(listOf("Earlier work (OpenAI, 2023) supports this."),
                listOf("References", "OpenAI. 2023. GPT-4 technical report.")).withIndex()) {
                val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600, 800, number + 1).create())
                lines.forEachIndexed { row, line -> page.canvas.drawText(line, 60f, 120f + row * 32f, paint) }
                document.finishPage(page)
            }
            pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context,
            org.readera.openreadera.data.db.AppDatabase::class.java).build()
        val repository = org.readera.openreadera.data.repository.BookRepository(db.bookDao(), db.bookmarkDao(),
            db.quoteDao(), db.collectionDao(), db.searchHistoryDao(), db.drawingStrokeDao())
        val id = repository.insertBook(org.readera.openreadera.data.model.Book(title = "Synthetic citation paper",
            filePath = pdf.path, format = "PDF"))
        val store = androidx.lifecycle.ViewModelStore()
        val scenario = ActivityScenario.launch(ReaderInkTestActivity::class.java)
        lateinit var model: org.readera.openreadera.ui.reader.ReaderViewModel
        lateinit var activity: ReaderInkTestActivity
        fun overlays(view: android.view.View): List<ReaderInkView> = when (view) {
            is ReaderInkView -> listOf(view)
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { overlays(view.getChildAt(it)) }
            else -> emptyList()
        }
        suspend fun await(block: () -> Boolean) = kotlinx.coroutines.withTimeout(20_000) {
            while (!block()) kotlinx.coroutines.delay(50)
        }
        try {
            scenario.onActivity {
                activity = it
                model = org.readera.openreadera.ui.reader.ReaderViewModel(id, repository,
                    org.readera.openreadera.data.preferences.ReaderPreferences(context), EngineManager(context), context)
                store.put("reader", model)
                activity.setContent { OpenReadEraTheme {
                    org.readera.openreadera.ui.reader.ReaderScreen(model, onBack = {}, onOpenDocumentDetails = {})
                } }
            }
            await { model.uiState.value.pageText[0]?.layout?.words?.isNotEmpty() == true }
            await {
                var ready = false
                instrumentation.runOnMainSync { ready = overlays(activity.window.decorView).any { it.isLaidOut && it.width > 0 && it.height > 0 } }
                ready && instrumentation.uiAutomation.rootInActiveWindow?.window?.isFocused == true
            }
            instrumentation.uiAutomation.waitForIdle(150, 5_000)
            val layout = model.uiState.value.pageText.getValue(0).layout!!
            val word = layout.words.first { PdfReferenceText.citationAt(layout, it)?.text?.contains("OpenAI") == true }
            var screenX = 0f; var screenY = 0f
            instrumentation.runOnMainSync {
                val view = overlays(activity.window.decorView).single()
                val rect = PageDrawingGeometry.fitRect(view.width.toFloat(), view.height.toFloat(), model.getPageAspectRatio(0))
                val location = IntArray(2); view.getLocationOnScreen(location)
                screenX = location[0] + rect.left + rect.width * word.bounds.centerX()
                screenY = location[1] + rect.top + rect.height * word.bounds.centerY()
            }
            assertEquals(targetContext.packageName, instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Synthetic citation tap at $screenX,$screenY\n") })
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val properties = MotionEvent.PointerProperties().apply { this.id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
                val coordinates = MotionEvent.PointerCoords().apply { this.x = screenX; this.y = screenY; pressure = .5f; size = .1f }
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(properties),
                    arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                SystemClock.sleep(40)
            }
            await { findNode("GPT-4 technical report") != null }
            assertEquals(0, model.uiState.value.currentPage)
            var close = checkNotNull(findNode("Cerrar"))
            while (!close.isClickable && close.parent != null) close = close.parent
            assertTrue(close.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            await { findNode("GPT-4 technical report") == null }
            instrumentation.uiAutomation.waitForIdle(150, 5_000)
            for (action in listOf(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE)) {
                val properties = MotionEvent.PointerProperties().apply { this.id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
                val coordinates = MotionEvent.PointerCoords().apply { this.x = screenX; this.y = screenY; pressure = 0f; size = .1f }
                val event = MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coordinates),
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            await { findNode("GPT-4 technical report") != null }
            var button = checkNotNull(findNode("Ver en página 2"))
            while (!button.isClickable && button.parent != null) button = button.parent
            assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            await { model.uiState.value.currentPage == 1 }
        } finally {
            instrumentation.runOnMainSync { activity.setContent {}; store.clear() }
            scenario.close()
            kotlinx.coroutines.delay(600)
            db.close()
            pdf.delete()
            synchronized(names) { names.forEach(targetContext::deleteSharedPreferences) }
        }
    }

    @Test fun tapAndHoverPreviewReferencesWhileWritingRemainsUntouched() {
        val scenario = ActivityScenario.launch(ReaderInkTestActivity::class.java)
        lateinit var view: ReaderInkView
        var opened = 0
        var writing = false
        fun configure() = view.configure(0, emptyList(), DrawingTool.PEN, "#111111", 4f,
            {}, {}, 1f, null,
            PageTextLayout("See [1].", listOf(PageTextWord(4, 7, RectF(.1f, .1f, .3f, .2f), 0))),
            {}, emptyList(), {}, writing, 1f)
        try {
            scenario.onActivity { activity ->
                activity.setContent {
                    var target by remember { mutableStateOf<CitationTarget?>(null) }
                    OpenReadEraTheme {
                        AndroidView(factory = { context -> ReaderInkView(context).also {
                            view = it
                            view.onCitation = { citation -> opened++; target = citation }
                            configure()
                        } })
                        target?.let { ReferencePreviewDialog(it,
                            load = { listOf(PaperReference("1", "Author. 2020. Synthetic reference preview.", 3)) },
                            onPage = { target = null }, onDismiss = { target = null }) }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            fun event(action: Int) {
                instrumentation.runOnMainSync {
                    val rect = PageDrawingGeometry.fitRect(view.width.toFloat(), view.height.toFloat(), 1f)
                    val time = SystemClock.uptimeMillis()
                    val motion = MotionEvent.obtain(time, time, action,
                        rect.left + rect.width * .2f, rect.top + rect.height * .15f, 0)
                    try {
                        if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_EXIT)
                            { motion.source = android.view.InputDevice.SOURCE_STYLUS; view.dispatchGenericMotionEvent(motion) } else view.dispatchTouchEvent(motion)
                    } finally { motion.recycle() }
                }
            }
            event(MotionEvent.ACTION_DOWN); event(MotionEvent.ACTION_UP)
            instrumentation.waitForIdleSync()
            assertEquals(1, opened)
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (findNode("Synthetic reference preview") == null &&
                SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertTrue(findNode("Synthetic reference preview") != null)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            screenshot?.let { bitmap ->
                File(instrumentation.targetContext.cacheDir, "reference-preview-synthetic.png")
                    .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            event(MotionEvent.ACTION_HOVER_MOVE)
            Thread.sleep(600)
            instrumentation.waitForIdleSync()
            assertEquals(2, opened)
            instrumentation.runOnMainSync { writing = true; configure() }
            event(MotionEvent.ACTION_HOVER_EXIT); event(MotionEvent.ACTION_HOVER_MOVE)
            event(MotionEvent.ACTION_DOWN); event(MotionEvent.ACTION_UP)
            Thread.sleep(600)
            assertEquals(2, opened)
        } finally { scenario.close() }
    }


}
