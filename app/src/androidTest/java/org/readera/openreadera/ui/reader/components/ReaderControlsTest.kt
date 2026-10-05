package org.readera.openreadera.ui.reader.components

import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readera.openreadera.data.model.ReaderColorTheme
import org.readera.openreadera.ui.theme.OpenReadEraTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.readera.openreadera.translation.NativeTextTranslator

@RunWith(AndroidJUnit4::class)
class ReaderControlsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun findNode(label: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { search(it)?.let { found -> return found } }
            }
            return null
        }
        val roots = listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow) +
            instrumentation.uiAutomation.windows.mapNotNull { it.root }
        return roots.filter { it.packageName?.toString() == instrumentation.targetContext.packageName }
            .firstNotNullOfOrNull(::search)
    }
    private fun findContainingText(fragment: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString()?.contains(fragment) == true) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { search(it)?.let { found -> return found } }
            return null
        }
        val roots = listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow) +
            instrumentation.uiAutomation.windows.mapNotNull { it.root }
        return roots.filter { it.packageName?.toString() == instrumentation.targetContext.packageName }
            .firstNotNullOfOrNull(::search)
    }

    private fun awaitContainingText(fragment: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            findContainingText(fragment)?.let { return it }
            SystemClock.sleep(30)
        }
        val roots = instrumentation.uiAutomation.windows.mapNotNull { it.root } +
            listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        val visible = roots.filter { it.packageName?.toString() == instrumentation.targetContext.packageName }
            .flatMap { root ->
                buildList { fun collect(node: AccessibilityNodeInfo) { node.text?.toString()?.let(::add); for (index in 0 until node.childCount) node.getChild(index)?.let(::collect) }; collect(root) }
            }.joinToString(" | ")
        val packageSummary = roots.map { it.packageName?.toString() }.distinct()
        throw AssertionError("Missing visible text containing: $fragment; app text: $visible; accessibility root packages: $packageSummary")
    }

    private fun awaitNode(label: String, condition: (AccessibilityNodeInfo) -> Boolean = { true }): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val named = findNode(label)
            if (named != null) {
                // Selected radio buttons expose state but intentionally omit a redundant click action.
                if (named.className == "android.widget.RadioButton" && condition(named)) return named
                var action: AccessibilityNodeInfo? = named
                while (action != null && !action.isClickable) action = action.parent
                if (action != null && condition(action)) return action
            }
            SystemClock.sleep(30)
        }
        val evidence = StringBuilder()
        fun visit(node: AccessibilityNodeInfo) {
            evidence.append("text=").append(node.text).append(" desc=").append(node.contentDescription)
                .append(" clickable=").append(node.isClickable).append(" actions=").append(node.actionList).append('\n')
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "reader-controls-failure.txt").writeText(evidence.toString())
        throw AssertionError("Reader control not available: $label")
    }

    @Test
    fun phoneAndWideThemesExposeRadioSelectionTouchTargetsAndLockActions() {
        ActivityScenario.launch(ReaderInkTestActivity::class.java).use { scenario ->
            for (width in listOf(360, 840)) {
                val theme = mutableStateOf(ReaderColorTheme.SYSTEM)
                val locked = mutableStateOf(false)
                var density = 1f
                scenario.onActivity { activity ->
                    density = activity.resources.displayMetrics.density
                    val configuration = Configuration(activity.resources.configuration).apply { screenWidthDp = width }
                    activity.setContent {
                        OpenReadEraTheme {
                            CompositionLocalProvider(LocalConfiguration provides configuration) {
                                Box(Modifier.width(width.dp)) {
                                    ReaderBottomBar(
                                        currentPage = 0, totalPages = 10, currentTheme = theme.value,
                                        fontSize = 20, brightness = 1f, isPdf = true, movementLocked = locked.value,
                                        onPageChange = {}, onNextPage = {}, onPreviousPage = {},
                                        onThemeChange = { theme.value = it }, onFontSizeChange = {},
                                        onBrightnessChange = {}, onToggleOrientation = {},
                                        onToggleMovementLock = { locked.value = !locked.value }, onAddToHistory = {}
                                    )
                                }
                            }
                        }
                    }
                }
                val choices = listOf(
                    "Tema claro" to ReaderColorTheme.DAY,
                    "Tema del sistema" to ReaderColorTheme.SYSTEM,
                    "Tema sepia" to ReaderColorTheme.SEPIA,
                    "Tema nocturno" to ReaderColorTheme.NIGHT,
                    "Tema OLED" to ReaderColorTheme.OLED
                )
                for ((label, expected) in choices) {
                    val control = awaitNode(label)
                    val bounds = Rect().also(control::getBoundsInScreen)
                    assertTrue("$label must be at least 48dp wide", bounds.width() >= 48 * density - 1)
                    assertTrue("$label must be at least 48dp high", bounds.height() >= 48 * density - 1)
                    assertEquals("android.widget.RadioButton", control.className.toString())
                    assertTrue(control.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    awaitNode(label) { it.isSelected || it.isChecked }
                    instrumentation.runOnMainSync { assertEquals(expected, theme.value) }
                    for ((otherLabel, otherTheme) in choices) {
                        if (otherTheme != expected) awaitNode(otherLabel) { !it.isSelected && !it.isChecked }
                    }
                }
                instrumentation.waitForIdleSync()
                val proof = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "reader-controls-$width.png")
                assertEquals(instrumentation.targetContext.packageName, instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { proof.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Reader controls proof: ${proof.path}\n") })
                assertTrue(awaitNode("Bloquear movimiento").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val unlock = awaitNode("Desbloquear movimiento") { it.stateDescription?.toString() == "Movimiento bloqueado" }
                assertEquals("Movimiento bloqueado", unlock.stateDescription?.toString())
                assertTrue(unlock.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val lock = awaitNode("Bloquear movimiento") { it.stateDescription?.toString() == "Movimiento desbloqueado" }
                assertEquals("Movimiento desbloqueado", lock.stateDescription?.toString())
                instrumentation.runOnMainSync { assertFalse(locked.value) }
            }
        }
    }
    private fun showTranslationDialog(
        scenario: ActivityScenario<ReaderInkTestActivity>,
        source: java.io.File,
        output: java.io.File,
        translator: NativeTextTranslator
    ) {
        scenario.onActivity { activity ->
            activity.setContent {
                OpenReadEraTheme {
                    DocumentTranslationDialog(
                        bookTitle = "Self-authored fixture",
                        currentPage = 0,
                        getCurrentPageText = { "Native fixture text" },
                        sourcePath = source.path,
                        onBookGenerated = {},
                        onDismiss = {},
                        translate = translator,
                        outputDirectory = output
                    )
                }
            }
        }
    }

    private fun assertAppOwnsFocus() {
        val expectedPackage = instrumentation.targetContext.packageName
        val root = instrumentation.uiAutomation.rootInActiveWindow
        assertEquals(expectedPackage, root?.packageName?.toString())
        val descriptor = instrumentation.uiAutomation.executeShellCommand("dumpsys window")
        val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader().use { it.readText() }
        val currentFocus = dump.lineSequence().firstOrNull { it.contains("mCurrentFocus=") }.orEmpty()
        val focusedApp = dump.lineSequence().firstOrNull { it.contains("mFocusedApp=") }.orEmpty()
        val accessibleWindow = root?.window
        assertTrue(
            "Focused app must own the active root and current window; root=${root?.packageName}, " +
                "windowFocused=${accessibleWindow?.isFocused}, current=$currentFocus, app=$focusedApp",
            expectedPackage in currentFocus && expectedPackage in focusedApp &&
                (accessibleWindow == null || accessibleWindow.isFocused)
        )
    }
    private fun tapOwned(label: String) {
        val target = awaitNode(label)
        assertAppOwnsFocus()
        var clickableTarget = target
        while (!clickableTarget.isClickable && clickableTarget.parent != null) clickableTarget = checkNotNull(clickableTarget.parent)
        assertTrue("$label resolves to a clickable accessibility node", clickableTarget.isClickable)
        val bounds = Rect()
        clickableTarget.getBoundsInScreen(bounds)
        assertFalse("$label has a visible touch target", bounds.isEmpty)
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0
            ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try {
                instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
        }
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
    }

    private fun proof(name: String) {
        assertAppOwnsFocus()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name)
        try { file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Translation UI proof: ${file.path}\n") })
    }

    private fun translationPdf(file: java.io.File) {
        PDFBoxResourceLoader.init(instrumentation.targetContext)
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(440f, 420f))
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 14f)
                stream.newLineAtOffset(40f, 300f)
                stream.showText("Original prose.")
                stream.endText()
            }
            document.save(file)
        }
    }

    @Test
    fun nativePdfOverflowIsVisibleAndDoesNotPublishOutput() {
        val root = java.io.File(instrumentation.targetContext.cacheDir, "translation-ui-${System.nanoTime()}").apply { mkdirs() }
        val source = java.io.File(root, "source.pdf")
        val output = java.io.File(root, "output").apply { mkdirs() }
        translationPdf(source)
        var providerCalls = 0
        ActivityScenario.launch(ReaderInkTestActivity::class.java).use { scenario ->
            showTranslationDialog(scenario, source, output) { _, _ ->
                providerCalls++
                Result.success("Expansion ".repeat(100))
            }
            tapOwned("Documento / Paper completo")
            proof("translation-pdf-full-mode.png")
            assertTrue(awaitContainingText("PDF: conserva páginas").text.toString().contains("hasta 15 %"))
            tapOwned("Crear copia traducida")
            val deadline = SystemClock.uptimeMillis() + 20_000
            var error: String? = null
            while (SystemClock.uptimeMillis() < deadline && error == null) {
                error = findNode("The complete translation does not fit at the permitted font size and leading")?.text?.toString()
                if (error == null) SystemClock.sleep(40)
            }
            if (error == null) proof("translation-pdf-overflow-unexpected-state.png")
            val visibleText = (instrumentation.uiAutomation.windows.mapNotNull { it.root } +
                listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow))
                .filter { it.packageName?.toString() == instrumentation.targetContext.packageName }
                .flatMap { root ->
                    buildList { fun collect(node: AccessibilityNodeInfo) { node.text?.toString()?.let(::add); for (index in 0 until node.childCount) node.getChild(index)?.let(::collect) }; collect(root) }
                }.joinToString(" | ")
            assertNotNull("Explicit PDF overflow error is shown; visible text=$visibleText", error)
            assertEquals(1, providerCalls)
            assertTrue("No native export is handed off", output.listFiles().isNullOrEmpty())
            proof("translation-pdf-overflow-error.png")
        }
        assertTrue(output.listFiles().isNullOrEmpty())
        root.deleteRecursively()
    }

    @Test
    fun kindleExportRequiresExplicitConsentAndCancelNeverCallsProvider() {
        val root = java.io.File(instrumentation.targetContext.cacheDir, "translation-kindle-${System.nanoTime()}").apply { mkdirs() }
        val source = java.io.File(root, "source.mobi").apply {
            writeBytes(ByteArray(68).also { "BOOKMOBI".toByteArray().copyInto(it, 60) })
        }
        val output = java.io.File(root, "output").apply { mkdirs() }
        var providerCalls = 0
        ActivityScenario.launch(ReaderInkTestActivity::class.java).use { scenario ->
            showTranslationDialog(scenario, source, output) { _, _ ->
                providerCalls++
                Result.success("Unexpected")
            }
            tapOwned("Documento / Paper completo")
            proof("translation-kindle-full-mode.png")
            assertTrue(awaitContainingText("Kindle: requiere confirmar esta exportación a EPUB").text.toString().contains("reflujo"))
            tapOwned("Crear copia traducida")
            assertNotNull(awaitContainingText("Confirmar conversión Kindle a EPUB"))
            assertTrue(findNode("Confirmar EPUB y traducir") != null)
            proof("translation-kindle-consent.png")
            tapOwned("Cancelar")
            assertNull(findNode("Confirmar conversión Kindle a EPUB"))
            assertEquals(0, providerCalls)
            assertTrue(output.listFiles().isNullOrEmpty())
        }
        root.deleteRecursively()
    }
}
