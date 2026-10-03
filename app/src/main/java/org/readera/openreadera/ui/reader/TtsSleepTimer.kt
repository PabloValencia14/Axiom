package org.readera.openreadera.ui.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class TtsSleepTimer(
    private val scope: CoroutineScope,
    private val onExpired: () -> Unit
) {
    private var job: Job? = null
    private val _minutes = MutableStateFlow<Int?>(null)
    val minutes: StateFlow<Int?> = _minutes.asStateFlow()

    fun start(minutes: Int) {
        require(minutes == 15 || minutes == 30 || minutes == 45 || minutes == 60)
        job?.cancel()
        _minutes.value = minutes
        job = scope.launch {
            delay(minutes * 60_000L)
            _minutes.value = null
            job = null
            onExpired()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _minutes.value = null
    }
}
