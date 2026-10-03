package org.readera.openreadera.ui.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TtsSleepTimerTest {
    @Test
    fun replacementExpiresOnlyAtNewDeadlineAndOnlyOnce() = runTest {
        var expiries = 0
        val timer = TtsSleepTimer(backgroundScope) { expiries++ }

        timer.start(15)
        advanceTimeBy(14 * 60_000L)
        runCurrent()
        timer.start(30)
        assertEquals(30, timer.minutes.value)
        advanceTimeBy(15 * 60_000L)
        runCurrent()
        assertEquals(0, expiries)
        assertEquals(30, timer.minutes.value)
        advanceTimeBy(15 * 60_000L - 1)
        runCurrent()
        assertEquals(0, expiries)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, expiries)
        assertNull(timer.minutes.value)
        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertEquals(1, expiries)
    }

    @Test
    fun cancelPreventsExpiry() = runTest {
        var expiries = 0
        val timer = TtsSleepTimer(backgroundScope) { expiries++ }
        timer.start(15)
        timer.cancel()
        advanceTimeBy(15 * 60_000L)
        runCurrent()
        assertEquals(0, expiries)
        assertNull(timer.minutes.value)
    }
}
