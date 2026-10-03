package org.readera.openreadera.ui.reader

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class ReaderHardwareEvent {
    PAGE_NEXT,
    PAGE_PREVIOUS,
}

data class ReaderHardwareInput(val event: ReaderHardwareEvent, val isStylus: Boolean = false)

object ReaderEventBus {
    private val _events = MutableSharedFlow<ReaderHardwareInput>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<ReaderHardwareInput> = _events.asSharedFlow()

    fun emit(event: ReaderHardwareEvent, isStylus: Boolean = false) {
        _events.tryEmit(ReaderHardwareInput(event, isStylus))
    }

    /** Shared with the isolated reader host so tests exercise the production key route. */
    fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return false
        val navigation = when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_PAGE_DOWN, android.view.KeyEvent.KEYCODE_BUTTON_2,
            android.view.KeyEvent.KEYCODE_BUTTON_B, android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
            309 -> ReaderHardwareEvent.PAGE_NEXT
            android.view.KeyEvent.KEYCODE_PAGE_UP, android.view.KeyEvent.KEYCODE_BUTTON_1,
            android.view.KeyEvent.KEYCODE_BUTTON_A, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            308 -> ReaderHardwareEvent.PAGE_PREVIOUS
            else -> return false
        }
        emit(navigation, event.keyCode == 308 || event.keyCode == 309 ||
            event.isFromSource(android.view.InputDevice.SOURCE_STYLUS))
        return true
    }
}

