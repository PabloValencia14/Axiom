package org.readera.openreadera.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale

enum class StrokeToolType {
    PEN,
    HIGHLIGHTER,
    MARKER,
    FREE_HIGHLIGHTER
}

data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val elapsedTimeMillis: Long? = null
)

data class DrawingPageAnchor(
    val chapterIndex: Int,
    val textOffset: Int
)

data class ParsedDrawingStroke(
    val points: List<StrokePoint>,
    val pageAnchor: DrawingPageAnchor?,
    val isPageNormalized: Boolean,
    val isRectangularHighlight: Boolean = false
)

@Entity(
    tableName = "drawing_strokes",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId"), Index(value = ["bookId", "page"])]
)
data class DrawingStroke(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val bookId: Long,
    val page: Int,
    val toolType: StrokeToolType = StrokeToolType.PEN,
    val colorHex: String = "#FFEB3B",
    val strokeWidth: Float = 4f,
    val pointsJson: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun parseData(): ParsedDrawingStroke {
        val firstSeparator = pointsJson.indexOf(';')
        val header = if (firstSeparator >= 0) pointsJson.substring(0, firstSeparator) else pointsJson
        val isVersion3 = header.startsWith(VERSION_3_PREFIX)
        val isTimed = header.startsWith(VERSION_4_PREFIX)
        val isNormalized = isVersion3 || header.startsWith(VERSION_2_PREFIX) || header.startsWith(VERSION_4_PREFIX)
        val body = if (isNormalized && firstSeparator >= 0) pointsJson.substring(firstSeparator + 1) else pointsJson
        val pageAnchor = if (isNormalized) parseAnchor(header) else null

        val points = body.split(';').mapNotNull { part ->
            val coords = part.split(',')
            if (coords.size !in 2..(if (isTimed) 4 else 3)) return@mapNotNull null
            val x = coords[0].toFloatOrNull() ?: return@mapNotNull null
            val y = coords[1].toFloatOrNull() ?: return@mapNotNull null
            val pressure = coords.getOrNull(2)?.toFloatOrNull() ?: 1f
            if (!x.isFinite() || !y.isFinite() || !pressure.isFinite()) return@mapNotNull null
            StrokePoint(
                x = if (isNormalized) x.coerceIn(0f, 1f) else x,
                y = if (isNormalized) y.coerceIn(0f, 1f) else y,
                pressure = pressure.coerceIn(if (isTimed) 0f else 0.1f, 1f),
                elapsedTimeMillis = coords.getOrNull(3)?.toLongOrNull()?.coerceAtLeast(0L)
            )
        }

        return ParsedDrawingStroke(points, pageAnchor, isNormalized, isVersion3)
    }

    fun pageAnchor(): DrawingPageAnchor? {
        val header = pointsJson.substringBefore(';')
        if (!header.startsWith(VERSION_2_PREFIX) && !header.startsWith(VERSION_3_PREFIX) && !header.startsWith(VERSION_4_PREFIX)) return null
        return parseAnchor(header)
    }

    companion object {
        private const val VERSION_2_PREFIX = "v2|"
        private const val VERSION_3_PREFIX = "v3|"
        private const val VERSION_4_PREFIX = "v4|"
        fun serializePoints(points: List<StrokePoint>, pageAnchor: DrawingPageAnchor? = null): String =
            serialize(VERSION_2_PREFIX, points, pageAnchor)

        fun serializeTimedPoints(points: List<StrokePoint>, pageAnchor: DrawingPageAnchor? = null): String =
            serialize(VERSION_4_PREFIX, points, pageAnchor)

        fun serializeRectangularHighlights(
            normalizedCorners: List<StrokePoint>,
            pageAnchor: DrawingPageAnchor? = null
        ): String = serialize(VERSION_3_PREFIX, normalizedCorners, pageAnchor)

        private fun serialize(prefix: String, points: List<StrokePoint>, pageAnchor: DrawingPageAnchor?): String {
            val header = "$prefix${pageAnchor?.chapterIndex ?: "-"}|${pageAnchor?.textOffset ?: "-"};"
            val body = points.joinToString(";") { point ->
                val pressure = if (prefix == VERSION_4_PREFIX) {
                    point.pressure.coerceIn(0f, 1f).toString()
                } else "%.3f".format(Locale.US, point.pressure.coerceIn(0.1f, 1f))
                val coordinates = if (prefix == VERSION_4_PREFIX) "${point.x},${point.y},$pressure"
                else "${"%.4f".format(Locale.US, point.x)},${"%.4f".format(Locale.US, point.y)},$pressure"
                if (prefix == VERSION_4_PREFIX) "$coordinates,${(point.elapsedTimeMillis ?: 0L).coerceAtLeast(0L)}"
                else coordinates
            }
            return header + body
        }

        private fun parseAnchor(header: String): DrawingPageAnchor? {
            val fields = header.split('|')
            if (fields.size != 3) return null
            val chapter = fields[1].toIntOrNull() ?: return null
            val offset = fields[2].toIntOrNull() ?: return null
            if (chapter < 0 || offset < 0) return null
            return DrawingPageAnchor(chapter, offset)
        }
    }
}
