package org.readera.openreadera.data.stats

internal class ReadingSession(
    private val clock: () -> Long,
    private val record: (Long, Int) -> Unit
) {
    private var started: Long? = null

    fun resume() { if (started == null) started = clock() }

    fun flush(pages: Int = 0) {
        val start = started ?: return
        val now = clock()
        if (now > start) record(now - start, pages)
        started = now
    }

    fun pause() { flush(); started = null }
}
