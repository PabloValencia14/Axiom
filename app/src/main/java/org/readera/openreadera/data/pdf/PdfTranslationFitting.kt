package org.readera.openreadera.data.pdf

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import java.text.BreakIterator
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PdfFittedParagraph(
    val paragraph: PdfParagraph,
    val font: PDFont,
    val size: Float,
    val leading: Float,
    val lines: List<String>,
    val ink: List<RectF>,
    val offsets: List<Float>
)

internal fun pdfTextInk(font: PDFont, text: String, size: Float, location: String): RectF {
    val input = ByteArrayInputStream(font.encode(text))
    val result = RectF()
    var x = 0f
    while (input.available() > 0) {
        val code = font.readCode(input)
        val path = pdfGlyphPath(font, code, location)
        path.transform(pdfAndroidMatrix(font.fontMatrix))
        path.transform(pdfAndroidMatrix(Matrix(size,0f,0f,size,x,0f)))
        val bounds = RectF(); path.computeBounds(bounds,true); result.union(bounds)
        x += font.getDisplacement(code).x * size
    }
    return result
}

internal suspend fun fitPdfParagraph(
    paragraph: PdfParagraph, translation: String, font: PDFont,
    accept: (PdfFittedParagraph) -> Boolean = { true }
): PdfFittedParagraph {
    val location = paragraph.first.location
    if (translation.isBlank()) rejectPdf("The provider returned an empty translation", location)
    val box = paragraph.box
    val originalSize = paragraph.size
    val glyphs = paragraph.shows.flatMap { show -> show.glyphs.filterNot { it.pageInk.isEmpty }.map { glyph ->
        val matrix = glyph.matrix.multiply(show.state.currentTransformationMatrix)
        val baseline = floatArrayOf(matrix.translateX,matrix.translateY).also(paragraph.inverse::mapPoints)
        baseline to RectF(glyph.pageInk).also(paragraph.inverse::mapRect)
    } }
    val baselines = glyphs.map { it.first[1] }.sortedDescending().fold(mutableListOf<Float>()) { rows, y ->
        if (rows.isEmpty() || kotlin.math.abs(rows.last()-y) > originalSize*.25f) rows.add(y)
        rows
    }
    val originalLeading = if (baselines.size > 1) baselines.zipWithNext().minOf { (a,b) -> a-b } else originalSize*1.2f
    // Short labels may use one em of adjacent space; the composer still rejects artwork/text collisions.
    val envelope = RectF(box).apply { inset(-originalSize*.20f,-originalSize*.20f) }
    val shortLabel = box.width() < originalSize*6f
    if (shortLabel) envelope.right += originalSize*.8f
    val continuation = glyphs.minOf { it.first[0] }.coerceAtMost(0f)
    val firstLineRight = glyphs.filter { kotlin.math.abs(it.first[1]) <= originalSize*.25f }.maxOf { it.second.right }
    fun width(line: Int) = (if (shortLabel) envelope.right else if (line == 0 && continuation < -originalSize) firstLineRight else box.right).coerceAtLeast(0f)-
        (if (line == 0) 0f else continuation)
    val pageTransform = paragraph.basis.multiply(paragraph.first.state.currentTransformationMatrix)
    val pageScale = minOf(kotlin.math.hypot(pageTransform.scaleX, pageTransform.shearY),
        kotlin.math.hypot(pageTransform.shearX, pageTransform.scaleY))
    if (!pageScale.isFinite() || pageScale <= 0f) rejectPdf("Invalid paragraph scale", location)
    val minimum = originalSize * if (baselines.size == 1) .45f else .65f
    val normalized = translation.replace("\r\n","\n").replace('\r','\n').replace('\t',' ')
    val breaker = BreakIterator.getLineInstance(Locale.ROOT)
    val maximumLines = (envelope.height() / maxOf(minimum * 1.05f, originalLeading * .90f)).toInt() + 1
    fun wrap(size: Float): List<String>? {
        val lines = mutableListOf<String>()
        for (hardLine in normalized.split('\n')) {
            breaker.setText(hardLine)
            var start = breaker.first(); var end = breaker.next(); var current = ""
            while (end != BreakIterator.DONE) {
                val chunk = hardLine.substring(start,end)
                val candidate = current + chunk
                if (font.getStringWidth(candidate.trimEnd())*size/1000f <= width(lines.size)) current=candidate
                else {
                    if (current.isBlank()) {
                        if (lines.isEmpty() && continuation < -originalSize &&
                            font.getStringWidth(chunk.trim())*size/1000f <= width(1)) {
                            lines += ""
                            current = chunk.trimStart()
                            start=end; end=breaker.next()
                            continue
                        }
                        return null
                    }
                    lines += current.trimEnd(); current=chunk.trimStart()
                    if (lines.size > maximumLines) return null
                    if (font.getStringWidth(current.trimEnd())*size/1000f > width(lines.size)) return null
                }
                start=end; end=breaker.next()
            }
            lines += current.trimEnd()
            if (lines.size > maximumLines) return null
        }
        return lines
    }
    val previousMinimum = maxOf(originalSize*.80f, minOf(originalSize,9f / pageScale))
    val sizes = (0..30).map { originalSize-(originalSize-previousMinimum)*it/30f } +
        (1..30).map { previousMinimum-(previousMinimum-minimum)*it/30f }
    for (size in sizes.distinct()) {
        currentCoroutineContext().ensureActive()
        val lines = wrap(size) ?: continue
        val outlines = lines.map { pdfTextInk(font,it,size,location) }
        for (leadingStep in 0..10) {
            val leading = originalLeading*(1f-(1f-.90f)*leadingStep/10f)
            if (lines.size>1 && leading < size*1.05f) continue
            val strokeExpansion = if (paragraph.first.renderingMode == 0) 0f else {
                val basis = paragraph.basis
                val basisScale = minOf(kotlin.math.hypot(basis.scaleX,basis.shearY),kotlin.math.hypot(basis.shearX,basis.scaleY))
                paragraph.first.strokeExpansion / basisScale
            }
            val inks = outlines.mapIndexed { index,outline -> RectF(outline).apply {
                offset(if (index == 0) 0f else continuation,-index*leading+paragraph.first.state.textState.rise)
                if (strokeExpansion > 0f) inset(-strokeExpansion,-strokeExpansion)
            } }
            if (inks.filterNot { it.isEmpty }.any { !envelope.contains(it) }) continue
            if (inks.zipWithNext().any { (a,b) -> RectF.intersects(a,b) }) continue
            val fitted = PdfFittedParagraph(paragraph,font,size,leading,lines,inks,List(lines.size) { if (it == 0) 0f else continuation })
            if (accept(fitted)) return fitted
            if (lines.size < 2) break
        }
    }
    rejectPdf("La traducción completa no cabe en el bloque original sin recortar texto ni invadir otros elementos. No se ha generado una copia parcial.", location)
}
