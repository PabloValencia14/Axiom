package org.readera.openreadera.ui.reader.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageDrawingGeometryTest {
    @Test
    fun fittedPageUsesCenteredLetterboxBounds() {
        val rect = PageDrawingGeometry.fitRect(1000f, 1000f, 0.5f)

        assertEquals(250f, rect.left, 0.01f)
        assertEquals(500f, rect.width, 0.01f)
        assertEquals(1000f, rect.height, 0.01f)
    }

    @Test
    fun normalizedCoordinatesRoundTripInsideThePage() {
        val rect = PageDrawingGeometry.fitRect(1000f, 1000f, 0.5f)
        val pagePoint = InkPoint(600f, 300f)

        val normalized = rect.normalize(pagePoint)
        val restored = rect.denormalize(normalized)

        assertEquals(pagePoint.x, restored.x, 0.01f)
        assertEquals(pagePoint.y, restored.y, 0.01f)
    }

    @Test
    fun eraserFindsStrokeBetweenItsRecordedSamples() {
        val stroke = listOf(InkPoint(0f, 0f), InkPoint(100f, 0f))

        assertTrue(PageDrawingGeometry.intersectsStroke(InkPoint(50f, 2f), stroke, 3f))
        assertFalse(PageDrawingGeometry.intersectsStroke(InkPoint(50f, 10f), stroke, 3f))
    }
    @Test
    fun highlighterSelectsOnlyTouchedWordsAndJoinsThemWithinTheirLine() {
        val page = InkRect(100f, 50f, 800f, 1000f)
        val words = listOf(
            InkTextWord(InkRect(0.1f, 0.2f, 0.1f, 0.04f), 0),
            InkTextWord(InkRect(0.22f, 0.2f, 0.1f, 0.04f), 0),
            InkTextWord(InkRect(0.34f, 0.2f, 0.1f, 0.04f), 0),
            InkTextWord(InkRect(0.1f, 0.3f, 0.3f, 0.04f), 1)
        )
        val touched = PageDrawingGeometry.touchedWordsForSegment(
            InkPoint(280f, 265f), InkPoint(440f, 270f), page, words).toSet()
        assertEquals(setOf(1, 2), touched)
        val band = PageDrawingGeometry.textHighlightBands(words, touched, page).single()
        assertEquals(276f, band.left, 0.01f)
        assertEquals(250f, band.top, 0.01f)
        assertEquals(176f, band.width, 0.01f)
        assertEquals(40f, band.height, 0.01f)
    }

    @Test
    fun blankPageAndUntouchedWordsNeverProduceFallbackRectangles() {
        val page = InkRect(250f, 0f, 500f, 1000f)
        val word = InkTextWord(InkRect(0.2f, 0.2f, 0.1f, 0.03f), 0)
        assertTrue(PageDrawingGeometry.touchedWordsForSegment(
            InkPoint(100f, 210f), InkPoint(200f, 210f), page, listOf(word)).isEmpty())
        assertTrue(PageDrawingGeometry.textHighlightBands(listOf(word), emptySet(), page).isEmpty())
        assertTrue(PageDrawingGeometry.touchedWordsForSegment(
            InkPoint(350f, 210f), InkPoint(400f, 210f), page, emptyList()).isEmpty())
    }

    @Test
    fun separateLinesAndUntouchedWordGapsStaySeparate() {
        val words = listOf(
            InkTextWord(InkRect(0.1f, 0.2f, 0.1f, 0.03f), 0),
            InkTextWord(InkRect(0.25f, 0.2f, 0.1f, 0.03f), 0),
            InkTextWord(InkRect(0.4f, 0.2f, 0.1f, 0.03f), 0),
            InkTextWord(InkRect(0.1f, 0.3f, 0.1f, 0.03f), 1)
        )
        val page = InkRect(0f, 0f, 600f, 850f)
        assertEquals(3, PageDrawingGeometry.textHighlightBands(words, setOf(0, 2, 3), page).size)
        val zoomed = InkRect(30f, -40f, 1200f, 1700f)
        val hit = PageDrawingGeometry.touchedWordsForSegment(
            InkPoint(170f, 315f), InkPoint(250f, 315f), zoomed, words)
        assertEquals(listOf(0), hit)
    }
}
