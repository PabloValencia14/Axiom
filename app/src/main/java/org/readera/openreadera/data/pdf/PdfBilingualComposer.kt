package org.readera.openreadera.data.pdf

import com.tom_roush.pdfbox.cos.COSName
import android.graphics.RectF
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.max

/** Emits the source page as clipped vector bands and flows its matching translations beneath them. */
internal class PdfBilingualComposer(
    private val document: PDDocument,
    private val fonts: PdfTranslationFonts
){
    internal data class Translation(val paragraph: PdfParagraph, val parts: List<PdfTranslatedPart>)

    private data class Placed(
        val translation: Translation,
        val x: Float,
        val width: Float,
        val top: Float,
        val bottom: Float,
        val column: Int
    )
    private data class Block(val top: Float, val bottom: Float, val placement: Placed?)
    private data class Band(val top: Float, val bottom: Float, val translations: List<Placed>)
    private data class FlowPlacement(val placement: Placed, val flow: Flow)
    private data class TranslationRow(val items: List<FlowPlacement>) {
        val height get() = items.maxOf { it.flow.height }
    }
    private data class PageGeometry(val width: Float, val height: Float, val rotation: Matrix) {
        fun bounds(source: RectF): RectF {
            val xs = floatArrayOf(source.left, source.right, source.right, source.left)
            val ys = floatArrayOf(source.bottom, source.bottom, source.top, source.top)
            val result = RectF()
            for (i in 0..3) {
                val x = rotation.scaleX * xs[i] + rotation.shearX * ys[i] + rotation.translateX
                val y = rotation.shearY * xs[i] + rotation.scaleY * ys[i] + rotation.translateY
                val displayY = height - y
                if (i == 0) result.set(x, displayY, x, displayY)
                else {
                    result.left = minOf(result.left, x); result.right = max(result.right, x)
                    result.top = minOf(result.top, displayY); result.bottom = max(result.bottom, displayY)
                }
            }
            return result
        }
    }

    private fun pageGeometry(page: PDPage): PageGeometry {
        val crop = page.cropBox
        val left = crop.lowerLeftX
        val bottom = crop.lowerLeftY
        val width = crop.width
        val height = crop.height
        return when ((page.rotation % 360 + 360) % 360) {
            0 -> PageGeometry(width, height, Matrix(1f, 0f, 0f, 1f, -left, -bottom))
            90 -> PageGeometry(height, width, Matrix(0f, -1f, 1f, 0f, -bottom, left + width))
            180 -> PageGeometry(width, height, Matrix(-1f, 0f, 0f, -1f, left + width, bottom + height))
            270 -> PageGeometry(height, width, Matrix(0f, 1f, -1f, 0f, bottom + height, -left))
            else -> error("Page rotation must be a right angle")
        }
    }

    fun compose(
        pages: List<PDPage>,
        roots: List<PdfContainer>,
        sourceParagraphs: List<PdfParagraph>,
        translations: List<Translation>
    ) {
        val forms = pages.map(::pageForm)
        val pagePlans = pages.mapIndexed { index, page ->
            val root = roots[index]
            val translated = translations.associateBy { it.paragraph }
            val crop = page.cropBox
            val geometry = pageGeometry(page)
            val width = geometry.width
            val height = geometry.height
            val blocks = sourceParagraphs.filter {
                it.first.container.id.substringBefore('/') == "page ${index + 1}"
            }.map { paragraph ->
                val bounds = geometry.bounds(RectF().also { rect -> paragraph.shows.forEach { rect.union(it.pageInk) } })
                val top = bounds.top.coerceIn(0f, height)
                val bottom = bounds.bottom.coerceIn(0f, height)
                val left = bounds.left.coerceIn(0f, width)
                val right = bounds.right.coerceIn(0f, width)
                val margin = 24f
                val spanning = right - left > width * .55f
                val columnWidth = (width - 3 * margin) / 2
                val column = if (spanning) -1 else if (left >= width / 2) 1 else 0
                val x = when (column) { -1 -> margin; 1 -> width / 2 + margin / 2; else -> margin }
                Block(top, bottom, translated[paragraph]?.let { translation ->
                    Placed(translation, x, if (spanning) width - 2 * margin else columnWidth, top, bottom, column)
                })
            }.toMutableList()
            val firstTextOrder = allPdfShows(root).filter { it.visible }.minOfOrNull { it.paintOrder } ?: Int.MAX_VALUE
            root.graphics.filterNot { graphic ->
                graphic.paintOrder < firstTextOrder &&
                    graphic.bounds.width() * graphic.bounds.height() >= crop.width * crop.height * .9f
            }.forEach { graphic ->
                val bounds = geometry.bounds(graphic.bounds)
                val top = bounds.top.coerceIn(0f, height)
                val bottom = bounds.bottom.coerceIn(0f, height)
                blocks += Block(top, bottom, null)
            }
            val ordered = blocks.sortedBy { it.top }
            val bands = mutableListOf<Band>()
            var cursor = 0f
            ordered.forEach { block ->
                val start = max(cursor, block.top)
                if (start > cursor) bands += Band(cursor, start, emptyList())
                val previous = bands.lastOrNull()
                val attached = listOfNotNull(block.placement)
                if (previous != null && block.top < previous.bottom) {
                    bands[bands.lastIndex] = previous.copy(bottom = max(previous.bottom, block.bottom),
                        translations = previous.translations + attached)
                } else {
                    bands += Band(start, max(start, block.bottom), attached)
                }
                cursor = max(cursor, block.bottom)
            }
            if (cursor < height) bands += Band(cursor, height, emptyList())
            if (bands.isEmpty()) bands += Band(0f, height, emptyList())
            val shows = allPdfShows(root)
            var bandIndex = 0
            while (bandIndex < bands.lastIndex) {
                val current = bands[bandIndex]
                val next = bands[bandIndex + 1]
                if (shows.any {
                        val bounds = geometry.bounds(it.pageInk)
                        bounds.top < current.bottom && bounds.bottom > current.bottom
                    }) {
                    bands[bandIndex] = current.copy(
                        bottom = next.bottom,
                        translations = current.translations + next.translations
                    )
                    bands.removeAt(bandIndex + 1)
                } else bandIndex++
            }
            bands
        }

        val outputPages = mutableListOf<PDPage>()
        pagePlans.forEachIndexed { pageIndex, bands ->
            val source = pages[pageIndex]
            val geometry = pageGeometry(source)
            val width = geometry.width
            if (geometry.height > MAX_PAGE_HEIGHT) rejectPdf("Source page exceeds supported PDF page height", "page $pageIndex")
            val margin = if (bands.any { it.translations.isNotEmpty() }) 24f else 0f
            val outputHeight = bands.fold(0f) { total, band ->
                total + band.bottom - band.top + flowRows(band).sumOf { (it.height + margin).toDouble() }.toFloat()
            } + margin * 2
            if (outputHeight <= MAX_PAGE_HEIGHT) {
                val output = PDPage(PDRectangle(width, max(geometry.height, outputHeight)))
                output.cropBox = output.mediaBox
                output.rotation = 0
                outputPages += output
                PDPageContentStream(document, output).use { stream ->
                    var yTop = output.mediaBox.height - margin
                    bands.forEach { band ->
                        val originalHeight = band.bottom - band.top
                        if (originalHeight > 0f) {
                            stream.saveGraphicsState()
                            stream.addRect(0f, yTop - originalHeight, width, originalHeight)
                            stream.clip()
                            val form = bandForm(forms[pageIndex], roots[pageIndex], band, geometry)
                            form.bBox = bandBBox(band, geometry)
                            stream.transform(Matrix.getTranslateInstance(0f, yTop - (geometry.height - band.top)))
                            stream.transform(geometry.rotation)
                            stream.drawForm(form)
                            stream.restoreGraphicsState()
                        }
                        yTop -= originalHeight
                        flowRows(band).forEach { row ->
                            val baseline = yTop - margin * .35f
                            drawRow(stream, row, baseline)
                            yTop = baseline - row.height - margin * .65f
                        }
                    }
                }
            } else {
                bands.forEach { band ->
                    val rows = flowRows(band)
                    val originalHeight = band.bottom - band.top
                    val available = MAX_PAGE_HEIGHT - originalHeight - margin * 2
                    val availableForFlow = available - margin
                    if (availableForFlow < MIN_LINE_HEIGHT)
                        rejectPdf("Source band leaves no room for translated text", "page $pageIndex band ${band.top}-${band.bottom}")
                    val chunks = mutableListOf<TranslationRow>()
                    rows.forEach { row -> chunks += splitRow(row, availableForFlow) }
                    val pagesForBand = mutableListOf<MutableList<TranslationRow>>()
                    var current = mutableListOf<TranslationRow>()
                    var used = 0f
                    chunks.forEach { row ->
                        if (used + row.height + margin > available && current.isNotEmpty()) {
                            pagesForBand += current
                            current = mutableListOf()
                            used = 0f
                        }
                        current += row
                        used += row.height + margin
                    }
                    if (current.isNotEmpty() || pagesForBand.isEmpty()) pagesForBand += current
                    pagesForBand.forEachIndexed { chunkIndex, pageRows ->
                        val paintsSource = chunkIndex == 0
                        val sourceHeight = if (paintsSource) originalHeight else 0f
                        val translatedHeight = pageRows.sumOf { (it.height + margin).toDouble() }.toFloat()
                        val pageHeight = max(1f, sourceHeight + translatedHeight + margin * 2)
                        if (pageHeight > MAX_PAGE_HEIGHT + .01f) rejectPdf("Translated line exceeds supported PDF page height", "page $pageIndex")
                        val output = PDPage(PDRectangle(width, pageHeight))
                        output.cropBox = output.mediaBox
                        output.rotation = 0
                        outputPages += output
                        PDPageContentStream(document, output).use { stream ->
                            var yTop = output.mediaBox.height - margin
                            if (paintsSource && originalHeight > 0f) {
                                stream.saveGraphicsState()
                                stream.addRect(0f, yTop - originalHeight, width, originalHeight)
                                stream.clip()
                                val form = bandForm(forms[pageIndex], roots[pageIndex], band, geometry)
                                form.bBox = bandBBox(band, geometry)
                                stream.transform(Matrix.getTranslateInstance(0f, yTop - (geometry.height - band.top)))
                                stream.transform(geometry.rotation)
                                stream.drawForm(form)
                                stream.restoreGraphicsState()
                                yTop -= originalHeight
                            }
                            pageRows.forEach { row ->
                                val baseline = yTop - margin * .35f
                                drawRow(stream, row, baseline)
                                yTop = baseline - row.height - margin * .65f
                            }
                        }
                    }
                }
            }
        }
        pages.toList().forEach(document::removePage)
        outputPages.forEach(document::addPage)
    }
    private fun pageForm(page: PDPage): PDFormXObject {
        val stream = PDStream(document)
        stream.createOutputStream(COSName.FLATE_DECODE).use { output ->
            page.contents.use { input -> input.copyTo(output) }
        }
        val crop = page.cropBox
        return PDFormXObject(stream).apply {
            setBBox(PDRectangle(crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height))
            resources = copyPdfResources(page.resources)
        }
    }
    private fun bandBBox(band: Band, geometry: PageGeometry): PDRectangle {
        val inverse = android.graphics.Matrix()
        if (!pdfAndroidMatrix(geometry.rotation).invert(inverse))
            rejectPdf("Page display geometry is singular", "band ${band.top}-${band.bottom}")
        val bounds = android.graphics.RectF(
            0f, geometry.height - band.bottom, geometry.width, geometry.height - band.top
        )
        inverse.mapRect(bounds)
        return PDRectangle(bounds.left, bounds.top, bounds.width(), bounds.height())
    }

    private fun flowRows(band: Band): List<TranslationRow> {
        val rows = mutableListOf<MutableList<FlowPlacement>>()
        band.translations.sortedBy { it.top }.forEach { placement ->
            val entry = FlowPlacement(placement, measureFlow(placement.translation, placement.width, placement.x))
            val peerRow = rows.lastOrNull { row ->
                placement.column >= 0 && row.all { other ->
                    other.placement.column >= 0 && other.placement.column != placement.column &&
                        placement.top < other.placement.bottom && placement.bottom > other.placement.top
                }
            }
            if (peerRow == null) rows += mutableListOf(entry) else peerRow += entry
        }
        return rows.map(::TranslationRow)
    }
    private fun drawRow(stream: PDPageContentStream, row: TranslationRow, baseline: Float) {
        row.items.forEach { item ->
            var itemBaseline = baseline
            item.flow.lines.forEach { line ->
                line.runs.forEach { run ->
                    val state = run.style.state.textState
                    stream.setNonStrokingColor(run.style.state.nonStrokingColor)
                    stream.setStrokingColor(run.style.state.strokingColor)
                    stream.setLineWidth(run.style.state.lineWidth)
                    stream.beginText()
                    stream.setFont(run.font, run.size)
                    stream.setRenderingMode(RenderingMode.fromInt(run.style.renderingMode))
                    stream.setCharacterSpacing(state.characterSpacing)
                    stream.setWordSpacing(state.wordSpacing)
                    stream.setTextRise(state.rise)
                    stream.newLineAtOffset(run.x, itemBaseline)
                    stream.showText(run.text)
                    stream.endText()
                }
                itemBaseline -= line.leading
            }
        }
    }

    private fun splitRow(row: TranslationRow, maxHeight: Float): List<TranslationRow> {
        if (row.height <= maxHeight) return listOf(row)
        val largestLeading = row.items.flatMap { it.flow.lines }.maxOfOrNull { it.leading } ?: MIN_LINE_HEIGHT
        val linesPerPage = (maxHeight / largestLeading).toInt().coerceAtLeast(1)
        val longest = row.items.maxOf { it.flow.lines.size }
        return (0 until longest step linesPerPage).map { start ->
            val items = row.items.mapNotNull { item ->
                val lines = item.flow.lines.drop(start).take(linesPerPage)
                if (lines.isEmpty()) null else item.copy(flow = Flow(lines))
            }
            TranslationRow(items)
        }
    }

    private companion object {
        const val MAX_PAGE_HEIGHT = 14_400f
        const val MIN_LINE_HEIGHT = 12f
    }

    private fun bandForm(
        base: PDFormXObject,
        container: PdfContainer,
        band: Band,
        geometry: PageGeometry
    ): PDFormXObject {
        val resources = copyPdfResources(container.resources)
        val childForms = container.children.mapValues { (_, child) ->
            val original = child.form ?: rejectPdf("Nested page form is missing", child.id)
            val clone = bandForm(original, child, band, geometry)
            resources.add(clone)
        }
        val shows = container.shows.associateBy { it.operation.index }
        val tokens = mutableListOf<Any>()
        container.operations.forEach { operation ->
            val show = shows[operation.index]
            val child = childForms[operation.index]
            when {
                child != null -> {
                    tokens += child
                    tokens += operation.operator
                }
                show != null && geometry.bounds(show.pageInk).let { it.bottom <= band.top || it.top >= band.bottom } ->
                    tokens.addAll(suppressTextShow(show))
                else -> {
                    tokens.addAll(operation.operands)
                    tokens += operation.operator
                }
            }
        }
        val stream = PDStream(document)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
        val result = PDFormXObject(stream)
        base.cosObject.keySet().forEach { key ->
            if (key !in setOf(COSName.LENGTH, COSName.FILTER, COSName.DECODE_PARMS, COSName.RESOURCES))
                result.cosObject.setItem(key, base.cosObject.getItem(key))
        }
        result.resources = resources
        return result
    }

    private fun suppressTextShow(show: PdfShow): List<Any> = buildList {
        val original = show.operation
        val state = show.state.textState
        var wordSpacing = state.wordSpacing
        var charSpacing = state.characterSpacing
        fun op(name: String, vararg values: Any) {
            addAll(values)
            add(Operator.getOperator(name))
        }
        if (original.operator.name == "\"") {
            wordSpacing = (original.operands[0] as COSNumber).floatValue()
            charSpacing = (original.operands[1] as COSNumber).floatValue()
            op("Tw", original.operands[0])
            op("Tc", original.operands[1])
        }
        if (original.operator.name == "'" || original.operator.name == "\"") op("T*")
        val advances = COSArray()
        fun suppress(string: COSString) {
            val font = state.font ?: rejectPdf("Text style has no source font", show.location)
            val input = java.io.ByteArrayInputStream(string.bytes)
            while (input.available() > 0) {
                val remaining = input.available()
                val code = font.readCode(input)
                val codeBytes = remaining - input.available()
                if (codeBytes <= 0) rejectPdf("Native text code has no stable byte boundary", show.location)
                var advance = font.getDisplacement(code).x * 1000f +
                    charSpacing * 1000f / state.fontSize
                if (code == 32 && codeBytes == 1) advance += wordSpacing * 1000f / state.fontSize
                advances.add(COSFloat(-advance))
            }
        }
        if (original.operator.name == "TJ") {
            (original.operands.single() as COSArray).forEach { value ->
                when (value) {
                    is COSString -> suppress(value)
                    is COSNumber -> advances.add(value)
                    else -> rejectPdf("Invalid TJ array", show.location)
                }
            }
        } else suppress(original.operands.last() as COSString)
        op("TJ", advances)
    }

    private data class TextRun(
        val text: String,
        val font: PDFont,
        val size: Float,
        val style: PdfShow,
        val x: Float
    )
    private data class Line(val runs: List<TextRun>, val leading: Float)
    private data class Flow(val lines: List<Line>) {
        val height get() = lines.fold(0f) { total, line -> total + line.leading }
    }

    private fun measureFlow(translation: Translation, width: Float, originX: Float = 24f): Flow {
        val lines = mutableListOf<Line>()
        var lineRuns = mutableListOf<TextRun>()
        var x = originX
        var lineLeading = 12f
        fun finishLine() {
            if (lineRuns.isNotEmpty()) lines += Line(lineRuns, lineLeading)
            lineRuns = mutableListOf()
            x = originX
            lineLeading = 12f
        }
        fun addText(value: String, font: PDFont, size: Float, style: PdfShow) {
            if (value.isEmpty()) return
            val last = lineRuns.lastOrNull()
            if (last != null && last.font === font && last.size == size && last.style === style) {
                lineRuns[lineRuns.lastIndex] = last.copy(text = last.text + value)
            } else lineRuns += TextRun(value, font, size, style, x)
            val textState = style.state.textState
            x += font.getStringWidth(value) * size / 1000f +
                value.codePointCount(0, value.length) * textState.characterSpacing +
                value.count { it == ' ' } * textState.wordSpacing
            lineLeading = max(lineLeading, max(size * 1.2f, textState.leading))
        }
        fun widthOf(value: String, font: PDFont, size: Float, style: PdfShow): Float {
            val state = style.state.textState
            return font.getStringWidth(value) * size / 1000f +
                value.codePointCount(0, value.length) * state.characterSpacing +
                value.count { it == ' ' } * state.wordSpacing
        }
        translation.parts.forEach { part ->
            val text: String
            val style: PdfShow
            when (part) {
                is PdfTranslatedPart.Text -> { text = part.text; style = part.sourceStyle }
                is PdfTranslatedPart.Protected -> { text = part.anchor.text; style = part.anchor.sourceStyle }
            }
            if (text.isEmpty()) return@forEach
            val sourceFont = style.state.textState.font ?: rejectPdf("Text style has no source font", style.location)
            val font = fonts.choose(sourceFont, text, style.location)
            val size = style.state.textState.fontSize
            val words = text.replace(Regex("\\s+"), " ").split(Regex("(?<=\\s)"))
            words.forEach { word ->
                if (word.isEmpty()) return@forEach
                if (word.codePointCount(0, word.length) == 1 && word == " ") {
                    if (x + widthOf(word, font, size, style) > originX + width && lineRuns.isNotEmpty()) finishLine()
                    addText(word, font, size, style)
                } else if (widthOf(word, font, size, style) <= width) {
                    if (x + widthOf(word, font, size, style) > originX + width && lineRuns.isNotEmpty()) finishLine()
                    addText(word, font, size, style)
                } else {
                    if (lineRuns.isNotEmpty()) finishLine()
                    var offset = 0
                    while (offset < word.length) {
                        val codePoint = word.codePointAt(offset)
                        val glyph = String(Character.toChars(codePoint))
                        val glyphWidth = widthOf(glyph, font, size, style)
                        if (x + glyphWidth > originX + width && lineRuns.isNotEmpty()) finishLine()
                        addText(glyph, font, size, style)
                        offset += Character.charCount(codePoint)
                    }
                }
            }
        }
        finishLine()
        return Flow(lines)
    }
}
