package org.readera.openreadera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readera.openreadera.data.model.DrawingPageAnchor
import org.readera.openreadera.data.model.DrawingStroke
import org.readera.openreadera.data.model.StrokePoint
import org.readera.openreadera.data.model.StrokeToolType
import org.readera.openreadera.engine.PageTextRange

class DrawingStrokeTest {
    @Test
    fun legacyStrokeCoordinatesRemainReadable() {
        val parsed = DrawingStroke(bookId = 1, page = 3, pointsJson = "0.25,0.4;0.8,0.9").parseData()

        assertFalse(parsed.isPageNormalized)
        assertNull(parsed.pageAnchor)
        assertEquals(listOf(StrokePoint(0.25f, 0.4f), StrokePoint(0.8f, 0.9f)), parsed.points)
    }

    @Test
    fun versionedStrokeKeepsPressureAndTextAnchor() {
        val anchor = DrawingPageAnchor(chapterIndex = 2, textOffset = 144)
        val points = listOf(StrokePoint(0.2f, 0.35f, 0.4f), StrokePoint(0.8f, 0.9f, 1f))
        val stroke = DrawingStroke(
            bookId = 1,
            page = 3,
            pointsJson = DrawingStroke.serializePoints(points, anchor)
        )

        val parsed = stroke.parseData()
        assertTrue(parsed.isPageNormalized)
        assertEquals(anchor, parsed.pageAnchor)
        assertEquals(points, parsed.points)
        assertEquals(anchor, stroke.pageAnchor())
    }

    @Test
    fun textAnchorSelectsTheReflowedPage() {
        val range = PageTextRange(chapterIndex = 2, startOffset = 100, endOffset = 200)

        assertTrue(range.contains(DrawingPageAnchor(2, 100)))
        assertTrue(range.contains(DrawingPageAnchor(2, 199)))
        assertFalse(range.contains(DrawingPageAnchor(2, 200)))
        assertFalse(range.contains(DrawingPageAnchor(1, 144)))
    }
    @Test
    fun rectangularHighlightKeepsNormalizedBoundsAndTextAnchor() {
        val anchor = DrawingPageAnchor(chapterIndex = 1, textOffset = 42)
        val corners = listOf(
            StrokePoint(0.12f, 0.34f),
            StrokePoint(0.78f, 0.38f),
            StrokePoint(0.2f, 0.45f),
            StrokePoint(0.66f, 0.49f)
        )
        val stroke = DrawingStroke(
            bookId = 1,
            page = 2,
            pointsJson = DrawingStroke.serializeRectangularHighlights(corners, anchor)
        )

        val parsed = stroke.parseData()
        assertTrue(parsed.isPageNormalized)
        assertTrue(parsed.isRectangularHighlight)
        assertEquals(corners, parsed.points)
        assertEquals(anchor, parsed.pageAnchor)
        assertEquals(anchor, stroke.pageAnchor())
    }

    @Test
    fun timedInkKeepsItsClockPressureAndAnchorWithoutChangingOldFormats() {
        val anchor = DrawingPageAnchor(4, 360)
        val points = listOf(StrokePoint(0.25f, 0.4f, 0.3f, 0L), StrokePoint(0.8f, 0.9f, 0.8f, 37L))
        val stroke = DrawingStroke(bookId = 1, page = 9,
            pointsJson = DrawingStroke.serializeTimedPoints(points, anchor))
        assertTrue(stroke.pointsJson.startsWith("v4|"))
        assertEquals(points, stroke.parseData().points)
        assertEquals(anchor, stroke.pageAnchor())
        assertEquals(anchor, stroke.parseData().pageAnchor)
        assertTrue(stroke.parseData().isPageNormalized)
        assertFalse(stroke.parseData().isRectangularHighlight)
        val old = DrawingStroke(bookId = 1, page = 9,
            pointsJson = DrawingStroke.serializePoints(points, anchor))
        assertTrue(old.pointsJson.startsWith("v2|"))
        assertTrue(old.parseData().points.all { it.elapsedTimeMillis == null })
        val bounded = DrawingStroke(bookId = 1, page = 1, pointsJson = "v4|-|-;2,-1,0,-7").parseData()
        assertEquals(StrokePoint(1f, 0f, 0f, 0L), bounded.points.single())
    }

    @Test
    fun everyStoredToolRetainsIdentityAndTimedSamples() {
        assertEquals(listOf("PEN", "HIGHLIGHTER", "MARKER", "FREE_HIGHLIGHTER"),
            StrokeToolType.entries.map { it.name })
        val points = listOf(StrokePoint(0.2f, 0.4f, 0.3f, 0L), StrokePoint(0.7f, 0.8f, 0.9f, 37L))
        for (tool in StrokeToolType.entries) {
            val record = DrawingStroke(bookId = 1, page = 0, toolType = tool,
                pointsJson = DrawingStroke.serializeTimedPoints(points))
            assertEquals(tool, StrokeToolType.valueOf(record.toolType.name))
            assertEquals(points, record.parseData().points)
        }
    }

    @Test
    fun timedPressureRetainsZeroLowFullAndExactHardwarePrecision() {
        val pressures = listOf(0f, 0.025f, 0.23456789f, 1f)
        val points = pressures.mapIndexed { i, pressure ->
            StrokePoint(0.20000003f + i * 0.00000003f, 0.456789f, pressure, (i / 2) * 12L)
        }
        val record = DrawingStroke(bookId = 1, page = 0, pointsJson = DrawingStroke.serializeTimedPoints(points))
        assertEquals(points, record.parseData().points)
        val old = DrawingStroke(bookId = 1, page = 0, pointsJson = "v4|-|-;0.2,0.4,0.100,0;0.3,0.5,0.900,20")
        assertEquals(listOf(0.1f, 0.9f), old.parseData().points.map { it.pressure })
        val legacy = DrawingStroke(bookId = 1, page = 0, pointsJson = "v2|-|-;0.2,0.4,0.025")
        assertEquals(0.1f, legacy.parseData().points.single().pressure, 0f)
        val nonfinite = DrawingStroke(bookId = 1, page = 0, pointsJson = "v4|-|-;0.2,0.4,NaN,0")
        assertTrue(nonfinite.parseData().points.isEmpty())
    }

}
