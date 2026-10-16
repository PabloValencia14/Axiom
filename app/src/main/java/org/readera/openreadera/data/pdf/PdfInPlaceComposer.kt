package org.readera.openreadera.data.pdf

import android.graphics.RectF
import android.graphics.Region
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.readera.openreadera.translation.TranslationRejectedException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Replaces only translated prose glyphs; page geometry and protected artwork remain untouched. */
internal class PdfInPlaceComposer(
    private val document: PDDocument,
    private val fonts: PdfTranslationFonts
) {
    internal data class Translation(val paragraph: PdfParagraph, val parts: List<PdfTranslatedPart>)

    suspend fun compose(roots: List<PdfContainer>, translations: List<Translation>) {
        val removed = translations.flatMap { it.parts }.filterIsInstance<PdfTranslatedPart.Text>()
            .filterNot { it.text.isBlank() }.flatMap { it.glyphs }.map { it.glyph }.toSet()
        translations.flatMap { it.parts }.filterIsInstance<PdfTranslatedPart.Protected>().forEach { part ->
            if (part.anchor.glyphs.any { it.glyph in removed })
                rejectPdf("A glyph cannot be split between prose and a protected formula or citation", part.anchor.sourceStyle.location)
        }
        val fixed = roots.associateWith { allPdfShows(it).flatMap { show -> show.glyphs }.filterNot { glyph -> glyph in removed } }
        val remaining = roots.associateWith { allPdfShows(it).flatMap { show -> show.glyphs }.filter { it in removed }.toMutableSet() }
        val painted = roots.associateWith { mutableListOf<List<RectF>>() }
        val fitted = translations.flatMap { translation ->
            currentCoroutineContext().ensureActive()
            translation.parts.filterIsInstance<PdfTranslatedPart.Text>().mapNotNull { part ->
                if (part.text.isBlank()) return@mapNotNull null
                val original = part.glyphs.sortedWith(compareBy<PdfGlyphRef> { it.show.operation.index }.thenBy { it.index })
                if (original.isEmpty()) return@mapNotNull null
                val paragraph = PdfParagraph(original.groupBy { it.show }.map { (source, refs) ->
                    PdfShow(source.container, source.operation, source.before.clone(), source.lineBefore.clone(),
                        source.state.clone(), source.epoch, source.paintOrder).apply {
                        glyphs += refs.map { it.glyph }
                        glyphs.forEach { ink.union(it.localInk); pageInk.union(it.pageInk); text.append(it.text) }
                        firstMatrix = (glyphs.firstOrNull { !it.pageInk.isEmpty } ?: glyphs.first()).matrix.clone()
                        lineAtShow = source.lineAtShow.clone()
                        visible = true
                        strokeExpansion = source.strokeExpansion
                    }
                }.toMutableList())
                paragraph.shows.forEach(::restoreAdvance)
                val font = fonts.choose(part.sourceStyle.state.textState.font!!, part.text, part.sourceStyle.location)
                val root = roots.single { paragraph.first.container.id.substringBefore('/') == it.id }
                remaining.getValue(root).removeAll(original.map { it.glyph }.toSet())
                val reserved = fixed.getValue(root) + remaining.getValue(root)
                var blocked: TranslationRejectedException? = null
                var acceptedInk = emptyList<RectF>()
                val replacement = try {
                    fitPdfParagraph(paragraph, part.text, font) { candidate ->
                        val ink = pageInk(candidate)
                        try {
                            checkCollision(root,candidate,ink,reserved,painted.getValue(root))
                            acceptedInk = ink
                            true
                        } catch (collision: TranslationRejectedException) {
                            blocked = collision
                            false
                        }
                    }
                } catch (failure: TranslationRejectedException) { throw blocked ?: failure }
                painted.getValue(root).add(acceptedInk)
                replacement
            }
        }
        roots.forEach { currentCoroutineContext().ensureActive(); rewrite(it, fitted) }
        // Raw PDF operators bypass PDPageContentStream's automatic font subsetting registration.
        fitted.map { it.font }.distinct().filter { it.willBeSubset() }.forEach { it.subset() }
    }

    private fun pageInk(replacement: PdfFittedParagraph): List<RectF> {
        val transform = pdfAndroidMatrix(replacement.paragraph.basis.multiply(
            replacement.paragraph.first.state.currentTransformationMatrix))
        return replacement.ink.filterNot { it.isEmpty }.map { RectF(it).also(transform::mapRect) }
    }

    private fun checkCollision(root: PdfContainer, replacement: PdfFittedParagraph, newInk: List<RectF>,
        unchangedGlyphs: List<PdfGlyph>, previousInk: List<List<RectF>>) {
        fun region(bounds: RectF) = Region(floor(bounds.left).toInt(), floor(bounds.top).toInt(),
            ceil(bounds.right).toInt(), ceil(bounds.bottom).toInt())
        val show = replacement.paragraph.first
        val oldArea = RectF()
        val oldInk = Region()
        replacement.paragraph.shows.forEach {
            oldArea.union(it.pageInk)
            oldInk.op(region(it.pageInk), Region.Op.UNION)
        }
        newInk.forEach { ink ->
            val outside = region(ink)
            outside.op(show.state.currentClippingPath, Region.Op.DIFFERENCE)
            if (!outside.isEmpty) rejectPdf("Translated ink crosses a crop or Form boundary", show.location)
            root.graphics.filter { !it.textDecoration && RectF.intersects(ink, it.bounds) }.forEach { graphic ->
                val envelope = region(oldArea)
                envelope.op(graphic.region, Region.Op.DIFFERENCE)
                val background = graphic.paintOrder < show.paintOrder && envelope.isEmpty
                if (!background) {
                    val introduced = region(ink)
                    introduced.op(graphic.region, Region.Op.INTERSECT)
                    introduced.op(oldInk, Region.Op.DIFFERENCE)
                    if (!introduced.isEmpty)
                        rejectPdf("Translated ink invades adjacent image or graphic geometry", show.location)
                }
            }
            if (unchangedGlyphs.any { RectF.intersects(ink, it.pageInk) })
                rejectPdf("Translated ink collides with protected or neighboring text", show.location)
        }
        if (previousInk.any { other -> newInk.any { x -> other.any { y -> RectF.intersects(x,y) } } })
            rejectPdf("Translated paragraphs collide",show.location)
    }

    private suspend fun rewrite(container: PdfContainer, replacements: List<PdfFittedParagraph>): PDFormXObject? {
        if (replacements.none { it.paragraph.first.container === container ||
                it.paragraph.first.container.id.startsWith("${container.id}/") }) return container.form
        val resources = container.resources
        val children = container.children.mapValues { (_, child) -> rewrite(child, replacements)!! }
        val local = replacements.filter { it.paragraph.first.container === container }
        val removed = local.flatMap { it.paragraph.shows.flatMap { show -> show.glyphs } }.toSet()
        val selected = container.shows.filter { show -> show.glyphs.any { it in removed } }
            .associateBy { it.operation.index }
        val insertions = local.groupBy { it.paragraph.first.operation.index }
        val tokens = mutableListOf<Any>()
        container.operations.forEach { operation ->
            currentCoroutineContext().ensureActive()
            insertions[operation.index].orEmpty().forEach { tokens.addAll(insertedParagraph(container, it)) }
            val show = selected[operation.index]
            val child = children[operation.index]
            when {
                show != null -> tokens.addAll(suppressedShow(show, removed))
                child != null -> {
                    tokens += resources.add(child)
                    tokens += Operator.getOperator("Do")
                }
                else -> { tokens.addAll(operation.operands); tokens += operation.operator }
            }
        }
        val stream = PDStream(document)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
        if (container.page != null) {
            container.page.resources = resources
            container.page.setContents(stream)
            return null
        }
        val original = container.form!!
        original.cosObject.keySet().forEach { key ->
            if (key !in setOf(COSName.LENGTH, COSName.FILTER, COSName.DECODE_PARMS, COSName.RESOURCES))
                stream.cosObject.setItem(key, original.cosObject.getItem(key))
        }
        return PDFormXObject(stream).also { it.resources = resources }
    }

    private fun insertedParagraph(container: PdfContainer, fitted: PdfFittedParagraph): List<Any> = buildList {
        val paragraph = fitted.paragraph
        val first = paragraph.first
        fun op(name: String,vararg values: Any) { addAll(values); add(Operator.getOperator(name)) }
        op("ET"); op("q"); op("BT")
        op("Tf",container.resources.add(fitted.font),COSFloat(fitted.size))
        op("Tc",COSFloat(0f)); op("Tw",COSFloat(0f)); op("Tz",COSFloat(100f))
        op("Ts",COSFloat(first.state.textState.rise)); op("Tr",COSInteger.get(first.renderingMode.toLong()))
        fitted.lines.forEachIndexed { index,line ->
            val matrix = paragraph.basis.clone().apply { concatenate(Matrix.getTranslateInstance(fitted.offsets[index],-index*fitted.leading)) }
            op("Tm",*matrixValues(matrix))
            if (fitted.font.willBeSubset()) line.codePoints().forEach { fitted.font.addToSubset(it) }
            op("Tj",COSString(fitted.font.encode(line)))
        }
        op("ET"); op("Q"); op("BT")
        op("Tm",*matrixValues(first.lineBefore))
        val displacement = COSArray().apply { add(COSFloat(restoreAdvance(first))) }
        op("TJ",displacement)
    }

    private fun restoreAdvance(show: PdfShow): Float {
        val line = show.lineAtShow
        val text = show.before
        val inverse = pdfAndroidMatrix(line).let { source -> android.graphics.Matrix().also {
            if (!source.invert(it)) rejectPdf("Singular text line matrix",show.location)
        } }
        val point = floatArrayOf(text.translateX,text.translateY); inverse.mapPoints(point)
        if (abs(point[1]) > .001f || line.scaleX != text.scaleX || line.scaleY != text.scaleY ||
            line.shearX != text.shearX || line.shearY != text.shearY)
            rejectPdf("Text and line matrices cannot be restored by an exact horizontal advance",show.location)
        return -point[0]*1000f/show.state.textState.fontSize
    }

    private fun matrixValues(matrix: Matrix): Array<Any> = arrayOf(COSFloat(matrix.scaleX),COSFloat(matrix.shearY),
        COSFloat(matrix.shearX),COSFloat(matrix.scaleY),COSFloat(matrix.translateX),COSFloat(matrix.translateY))

    private fun suppressedShow(show: PdfShow, removed: Set<PdfGlyph>): List<Any> = buildList {
        val original = show.operation
        val state = show.state.textState
        var wordSpacing = state.wordSpacing
        var charSpacing = state.characterSpacing
        fun op(name: String, vararg values: Any) { addAll(values); add(Operator.getOperator(name)) }
        if (original.operator.name == "\"") {
            wordSpacing = (original.operands[0] as COSNumber).floatValue()
            charSpacing = (original.operands[1] as COSNumber).floatValue()
            op("Tw", original.operands[0]); op("Tc", original.operands[1])
        }
        if (original.operator.name == "'" || original.operator.name == "\"") op("T*")
        val array = COSArray()
        var glyphIndex = 0
        fun replace(string: COSString) {
            val input = ByteArrayInputStream(string.bytes)
            val font = state.font!!
            while (input.available() > 0) {
                val before = input.available()
                val code = font.readCode(input)
                val consumed = before - input.available()
                if (consumed <= 0) rejectPdf("Unstable text code boundary", show.location)
                val glyph = show.glyphs.getOrNull(glyphIndex++)
                    ?: rejectPdf("Missing source glyph during replacement", show.location)
                if (glyph in removed) {
                    var advance = font.getDisplacement(code).x * 1000f + charSpacing * 1000f / state.fontSize
                    if (code == 32 && consumed == 1) advance += wordSpacing * 1000f / state.fontSize
                    array.add(COSFloat(-advance))
                } else array.add(COSString(glyph.sourceCode))
            }
        }
        if (original.operator.name == "TJ") {
            (original.operands.single() as COSArray).forEach { value ->
                when (value) {
                    is COSString -> replace(value)
                    is COSNumber -> array.add(value)
                    else -> rejectPdf("Invalid TJ array", show.location)
                }
            }
        } else replace(original.operands.last() as COSString)
        if (glyphIndex != show.glyphs.size) rejectPdf("Source glyph count changed", show.location)
        op("TJ", array)
    }
}
