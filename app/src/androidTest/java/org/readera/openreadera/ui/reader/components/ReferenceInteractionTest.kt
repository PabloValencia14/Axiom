package org.readera.openreadera.ui.reader.components

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
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
            if (node.text?.toString()?.contains(label, ignoreCase = true) == true || node.contentDescription?.toString()?.contains(label, ignoreCase = true) == true) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { search(it)?.let { value -> return value } }
            return null
        }
        return (instrumentation.uiAutomation.windows.mapNotNull { it.root } + listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow))
            .filter { it.packageName?.toString() == instrumentation.targetContext.packageName }.firstNotNullOfOrNull(::search)
    }


    @Test fun realReaderTapOpensReferenceAndJumpsToItsBibliographyPage() = checkReader()

    @Test fun doublePageZoomKeepsFingerAndHoverReferencesAligned() = checkReader(doublePage = true, zoom = true)

    @Test fun doublePageZoomKeepsRightPageReferencesAligned() = checkReader(doublePage = true, zoom = true, rightPage = true)

    private fun checkReader(doublePage: Boolean = false, zoom: Boolean = false, rightPage: Boolean = false) = kotlinx.coroutines.runBlocking {
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
                listOf("Earlier work (OpenAI, 2023) supports this."),
                listOf("References", "OpenAI. 2023. GPT-4 technical report.")).withIndex()) {
                val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600, 800, number + 1).create())
                lines.forEachIndexed { row, line -> page.canvas.drawText(line, if (number == 0) 300f else 60f,
                    if (number < 2) 400f else 120f + row * 32f, paint) }
                document.finishPage(page)
            }
            pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        InstrumentationRegistry.getArguments().getString("referencePaperPath")?.let { File(it).copyTo(pdf, overwrite = true) }
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
                if (doublePage || InstrumentationRegistry.getArguments().getString("referenceDoublePage") == "true")
                    model.updateViewMode(org.readera.openreadera.data.model.ReaderViewMode.DOUBLE_PAGE)
                activity.setContent { OpenReadEraTheme {
                    org.readera.openreadera.ui.reader.ReaderScreen(model, onBack = {}, onOpenDocumentDetails = {})
                } }
            }
            val citationPage = if (rightPage) 1 else 0
            await { model.uiState.value.pageText[citationPage]?.layout?.words?.isNotEmpty() == true }
            await {
                var ready = false
                instrumentation.runOnMainSync { ready = overlays(activity.window.decorView).any { it.isLaidOut && it.width > 0 && it.height > 0 } }
                ready && instrumentation.uiAutomation.rootInActiveWindow?.window?.isFocused == true
            }
            instrumentation.uiAutomation.waitForIdle(150, 5_000)
            if (zoom || InstrumentationRegistry.getArguments().getString("referenceZoom") == "true") {
                var centerX = 0f; var centerY = 0f
                instrumentation.runOnMainSync {
                    val decor = activity.window.decorView
                    val origin = IntArray(2); decor.getLocationOnScreen(origin)
                    centerX = origin[0] + decor.width / 2f; centerY = origin[1] + decor.height / 2f
                }
                val time = SystemClock.uptimeMillis()
                fun send(action: Int, count: Int, radius: Float) {
                    val pointers = Array(count) { i -> MotionEvent.PointerProperties().apply { this.id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                    val coordinates = Array(count) { i -> MotionEvent.PointerCoords().apply {
                        x = centerX + if (i == 0) -radius else radius; y = centerY; pressure = 1f; size = .01f
                    } }
                    val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, count, pointers, coordinates,
                        0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                    SystemClock.sleep(20)
                }
                send(MotionEvent.ACTION_DOWN, 1, 120f)
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 120f)
                for (step in 1..12) send(MotionEvent.ACTION_MOVE, 2, 120f * (1f + 1.5f * step / 12))
                send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 300f)
                send(MotionEvent.ACTION_UP, 1, 300f)
                instrumentation.uiAutomation.waitForIdle(150, 5_000)
            }
            val layout = model.uiState.value.pageText.getValue(citationPage).layout!!
            val word = layout.words.first { PdfReferenceText.citationAt(layout, it)?.text?.contains("OpenAI") == true }
            var screenX = 0f; var screenY = 0f
            instrumentation.runOnMainSync {
                val view = overlays(activity.window.decorView)[citationPage]
                val rect = PageDrawingGeometry.fitRect(view.width.toFloat(), view.height.toFloat(), model.getPageAspectRatio(citationPage))
                val location = IntArray(2); view.getLocationOnScreen(location)
                val scale = ReaderInkView::class.java.getDeclaredField("inputScale").apply { isAccessible = true }.getFloat(view)
                screenX = location[0] + (rect.left + rect.width * word.bounds.centerX()) * scale
                screenY = location[1] + (rect.top + rect.height * word.bounds.centerY()) * scale
            }
            assertEquals(targetContext.packageName, instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Citation tap at $screenX,$screenY\n") })
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val properties = MotionEvent.PointerProperties().apply { this.id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val coordinates = MotionEvent.PointerCoords().apply { this.x = screenX; this.y = screenY; pressure = .5f; size = .1f }
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(properties),
                    arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                SystemClock.sleep(40)
            }
            await { findNode("GPT-4 technical report") != null }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Finger tap opened reference\n") })
            assertEquals(0, model.uiState.value.currentPage)
            var close = checkNotNull(findNode("Cerrar"))
            while (!close.isClickable && close.parent != null) close = close.parent
            assertTrue(close.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            await { findNode("GPT-4 technical report") == null }
            instrumentation.uiAutomation.waitForIdle(150, 5_000)
            fun hover(vararg actions: Int) { for (action in actions) {
                val properties = MotionEvent.PointerProperties().apply { this.id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
                val coordinates = MotionEvent.PointerCoords().apply { this.x = screenX; this.y = screenY; pressure = 0f; size = .1f }
                val event = MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coordinates),
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            } }
            instrumentation.runOnMainSync { model.setWritingMode(true) }
            instrumentation.uiAutomation.waitForIdle(150, 5_000)
            hover(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE)
            kotlinx.coroutines.delay(650)
            assertNull("Writing must not open reference previews", findNode("GPT-4 technical report"))
            hover(MotionEvent.ACTION_HOVER_EXIT)
            instrumentation.runOnMainSync { model.setWritingMode(false) }
            instrumentation.uiAutomation.waitForIdle(150, 5_000)
            hover(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE)
            await { findNode("GPT-4 technical report") != null }
            val referencePage = model.findReferences(PdfReferenceText.citationAt(layout, word)!!).first().page
            var button = checkNotNull(findNode("Ver en página ${referencePage + 1}"))
            while (!button.isClickable && button.parent != null) button = button.parent
            assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            await { model.uiState.value.currentPage == referencePage }
        } finally {
            instrumentation.runOnMainSync { activity.setContent {}; store.clear() }
            scenario.close()
            kotlinx.coroutines.delay(600)
            db.close()
            pdf.delete()
            synchronized(names) { names.forEach(targetContext::deleteSharedPreferences) }
        }
    }

}
