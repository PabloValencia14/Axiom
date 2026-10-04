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
        val roots = instrumentation.uiAutomation.windows.mapNotNull { it.root } +
            listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        return roots.filter { it.packageName?.toString() == instrumentation.targetContext.packageName }
            .firstNotNullOfOrNull(::search)
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
}
