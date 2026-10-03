package org.readera.openreadera.engine

import android.graphics.RectF
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/** Unchecked so PDFBox's recoverable IOException handling for Form /Do cannot swallow it. */
internal class NativePageTextAbortException : RuntimeException("Native PDF page extraction aborted")

/** PDFBox emits normalized words with their contributing glyphs, before output separators. */
internal class PdfPageTextExtractor(private val page: PDPage, pageIndex: Int) : PDFTextStripper() {
    private val text = StringBuilder()
    private val words = ArrayList<PageTextWord>()
    private var lineIndex = 0
    private var glyphCount = 0
    private val crop = page.cropBox
    private val rotation = ((page.rotation % 360) + 360) % 360
    private val width = if (rotation == 90 || rotation == 270) crop.height else crop.width
    private val height = if (rotation == 90 || rotation == 270) crop.width else crop.height

    init {
        startPage = pageIndex + 1
        endPage = pageIndex + 1
        sortByPosition = true
        setShouldSeparateByBeads(false)
    }

    fun extract(document: PDDocument): PageTextLayout {
        // Output is collected in callbacks; no second full-page StringWriter copy is needed.
        writeText(document, object : java.io.Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) = Unit
            override fun flush() = Unit
            override fun close() = Unit
        })
        return PageTextLayout(text.toString(), words)
    }

    override fun processTextPosition(position: TextPosition) {
        if (++glyphCount > 100_000) throw NativePageTextAbortException()
        super.processTextPosition(position)
    }

    override fun showTextStrings(array: COSArray) {
        // Inherited malformed-TJ handling logs operand.toString(), including private COSStrings.
        for (operand in array) {
            if (operand !is COSString && operand !is COSNumber) throw NativePageTextAbortException()
        }
        super.showTextStrings(array)
    }

    override fun writeString(value: String, positions: MutableList<TextPosition>) {
        if (value.isBlank()) return
        // Explicit space glyphs can make a PDFBox callback contain several words. Split using
        // actual glyphs, not proportional slices. Also bypass PDFBox's char-based bidi reversal
        // for supplementary characters so UTF-16 surrogate pairs stay intact.
        if (value.any(Char::isWhitespace) || value.any(Character::isSurrogate)) {
            appendGlyphWords(positions)
            return
        }
        if (text.length + value.length > 100_000 || words.size >= 20_000) {
            throw NativePageTextAbortException()
        }
        val start = text.length
        text.append(value)
        var box: RectF? = null
        for (position in positions) {
            if (position.unicode.isBlank()) continue
            val glyph = glyphBounds(position) ?: continue
            if (box == null) box = glyph else box.union(glyph)
        }
        if (box != null) words += PageTextWord(start, text.length, box, lineIndex)
    }

    private fun appendGlyphWords(positions: List<TextPosition>) {
        var start = text.length
        var box: RectF? = null
        fun finishWord() {
            box?.let {
                if (words.size >= 20_000) throw NativePageTextAbortException()
                words += PageTextWord(start, text.length, it, lineIndex)
            }
            box = null
        }
        for (position in positions) {
            val unicode = position.unicode
            if (text.length + unicode.length > 100_000) throw NativePageTextAbortException()
            val glyph = glyphBounds(position)
            var offset = 0
            while (offset < unicode.length) {
                val codePoint = unicode.codePointAt(offset)
                val next = offset + Character.charCount(codePoint)
                if (Character.isWhitespace(codePoint)) {
                    finishWord()
                    text.append(unicode, offset, next)
                    start = text.length
                } else {
                    text.append(unicode, offset, next)
                    if (glyph != null) {
                        val previous = box
                        if (previous == null) box = RectF(glyph) else previous.union(glyph)
                    }
                }
                offset = next
            }
        }
        finishWord()
    }

    override fun writeWordSeparator() {
        if (text.isNotEmpty() && !text.last().isWhitespace()) text.append(' ')
    }

    override fun writeLineSeparator() = newLine()
    override fun writeParagraphEnd() = newLine()

    private fun newLine() {
        if (text.isNotEmpty() && text.last() != '\n') {
            text.append('\n')
            lineIndex++
        }
    }

    private fun glyphBounds(position: TextPosition): RectF? {
        // LegacyPDFStreamEngine already subtracts the crop origin from this matrix. Direction-
        // adjusted x/y ignore page rotation, so transform the glyph's crop-local baseline instead.
        val matrix = position.textMatrix
        val x = matrix.translateX
        val y = matrix.translateY
        val advance = position.widthDirAdj
        val ascent = position.heightDir
        val local = when (position.dir.toInt()) {
            90 -> RectF(x - ascent, y, x, y + advance)
            180 -> RectF(x - advance, y - ascent, x, y)
            270 -> RectF(x, y - advance, x + ascent, y)
            else -> RectF(x, y, x + advance, y + ascent)
        }
        // PDF crop coordinates are bottom-left; rendered bitmaps are top-left after /Rotate.
        val rendered = when (rotation) {
            90 -> RectF(local.top, local.left, local.bottom, local.right)
            180 -> RectF(crop.width - local.right, local.top, crop.width - local.left, local.bottom)
            270 -> RectF(crop.height - local.bottom, crop.width - local.right,
                crop.height - local.top, crop.width - local.left)
            else -> RectF(local.left, crop.height - local.bottom, local.right, crop.height - local.top)
        }
        return normalizedTextBounds(rendered.left, rendered.top, rendered.right, rendered.bottom, width, height)
    }
}
