package org.readera.openreadera.data.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingSessionTest {
    @Test fun longPagesAndLastIntervalCountButBackgroundTimeDoesNot() {
        var clock = 0L
        var total = 0L
        var pages = 0
        val session = ReadingSession({ clock }) { ms, count -> total += ms; pages += count }
        session.resume()
        clock += 600_000
        session.flush(1)
        clock += 90_000
        session.pause()
        clock += 900_000
        session.pause()
        session.resume()
        session.resume()
        clock += 10_000
        session.pause()
        assertEquals(700_000L, total)
        assertEquals(1, pages)
    }
}
