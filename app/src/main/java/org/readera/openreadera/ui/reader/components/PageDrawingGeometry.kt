package org.readera.openreadera.ui.reader.components

import kotlin.math.hypot

internal data class InkPoint(val x: Float, val y: Float)

internal data class InkRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    fun contains(point: InkPoint): Boolean =
        point.x in left..right && point.y in top..bottom

    fun clamp(point: InkPoint): InkPoint = InkPoint(
        x = point.x.coerceIn(left, right),
        y = point.y.coerceIn(top, bottom)
    )

    fun normalize(point: InkPoint): InkPoint = InkPoint(
        x = ((point.x - left) / width).coerceIn(0f, 1f),
        y = ((point.y - top) / height).coerceIn(0f, 1f)
    )

    fun denormalize(point: InkPoint): InkPoint = InkPoint(
        x = left + point.x * width,
        y = top + point.y * height
    )
}

internal data class InkTextWord(val bounds: InkRect, val lineIndex: Int)

internal object PageDrawingGeometry {
    fun fitRect(canvasWidth: Float, canvasHeight: Float, pageAspectRatio: Float): InkRect {
        if (canvasWidth <= 0f || canvasHeight <= 0f) return InkRect(0f, 0f, 0f, 0f)
        val aspect = pageAspectRatio.takeIf { it.isFinite() && it > 0f } ?: (canvasWidth / canvasHeight)
        val canvasAspect = canvasWidth / canvasHeight
        val width: Float
        val height: Float
        if (canvasAspect > aspect) {
            height = canvasHeight
            width = height * aspect
        } else {
            width = canvasWidth
            height = width / aspect
        }
        return InkRect((canvasWidth - width) / 2f, (canvasHeight - height) / 2f, width, height)
    }

    fun intersectsStroke(point: InkPoint, points: List<InkPoint>, radius: Float): Boolean {
        if (points.isEmpty()) return false
        if (points.size == 1) return distance(point, points[0]) <= radius
        return points.zipWithNext().any { (start, end) -> distanceToSegment(point, start, end) <= radius }
    }

    private fun distance(point: InkPoint, other: InkPoint): Float = hypot(point.x - other.x, point.y - other.y)

    private fun distanceToSegment(point: InkPoint, start: InkPoint, end: InkPoint): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return distance(point, start)
        val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(point.x - (start.x + t * dx), point.y - (start.y + t * dy))
    }
    fun touchedWordsForSegment(
        start: InkPoint,
        end: InkPoint,
        pageRect: InkRect,
        words: List<InkTextWord>
    ): List<Int> = words.indices.filter { index ->
        val box = wordRect(words[index], pageRect)
        // Small targeting tolerance, not brush width: broad tools must not select adjacent words/lines.
        val slop = minOf(2f, box.height * 0.12f)
        segmentIntersectsRect(start, end,
            InkRect(box.left - slop, box.top - slop, box.width + slop * 2f, box.height + slop * 2f))
    }

    fun textHighlightBands(words: List<InkTextWord>, selected: Set<Int>, pageRect: InkRect): List<InkRect> {
        val bands = ArrayList<InkRect>()
        var previousIndex = -2
        var previousLine = -1
        for (index in selected.sorted()) {
            val word = words[index]
            val box = wordRect(word, pageRect)
            if (index == previousIndex + 1 && word.lineIndex == previousLine) {
                val previous = bands.removeAt(bands.lastIndex)
                val left = minOf(previous.left, box.left)
                val top = minOf(previous.top, box.top)
                bands.add(InkRect(left, top, maxOf(previous.right, box.right) - left,
                    maxOf(previous.bottom, box.bottom) - top))
            } else bands.add(box)
            previousIndex = index
            previousLine = word.lineIndex
        }
        return bands
    }

    private fun wordRect(word: InkTextWord, pageRect: InkRect): InkRect = word.bounds.let {
        InkRect(pageRect.left + it.left * pageRect.width, pageRect.top + it.top * pageRect.height,
            it.width * pageRect.width, it.height * pageRect.height)
    }

    private fun segmentIntersectsRect(start: InkPoint, end: InkPoint, rect: InkRect): Boolean {
        var enter = 0f
        var leave = 1f
        val dx = end.x - start.x
        val dy = end.y - start.y

        fun clip(p: Float, q: Float): Boolean {
            if (p == 0f) return q >= 0f
            val ratio = q / p
            if (p < 0f) {
                if (ratio > leave) return false
                enter = maxOf(enter, ratio)
            } else {
                if (ratio < enter) return false
                leave = minOf(leave, ratio)
            }
            return true
        }

        return clip(-dx, start.x - rect.left) &&
            clip(dx, rect.right - start.x) &&
            clip(-dy, start.y - rect.top) &&
            clip(dy, rect.bottom - start.y)
    }
}
