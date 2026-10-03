package org.readera.openreadera.ui.reader.components

import androidx.activity.ComponentActivity

/** Hosts the real reader with test-owned repositories, without MainActivity's storage scanner. */
class ReaderInkTestActivity : ComponentActivity() {
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        org.readera.openreadera.ui.reader.ReaderEventBus.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
}
