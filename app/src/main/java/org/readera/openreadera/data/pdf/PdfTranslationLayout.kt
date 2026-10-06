package org.readera.openreadera.data.pdf

import android.content.Context
import android.graphics.Path
import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import kotlin.math.abs

internal data class PdfTextSegment(val text: String, val isMathematics: Boolean, val start: Int, val end: Int)
internal data class PdfTextRun(val isMathematics: Boolean, val glyphs: List<PdfGlyph>, val text: String)

internal fun splitPdfShow(show: PdfShow): List<PdfTextRun> {
    val math=splitPdfText(show.text.toString()).filter { it.isMathematics }
    if (math.isEmpty()) return emptyList()
    val groups=mutableListOf<MutableList<PdfGlyph>>()
    val kinds=mutableListOf<Boolean>()
    show.glyphs.forEach { glyph ->
        val isMath=glyph.text.all { !it.isLetterOrDigit() && !it.isWhitespace() } ||
            math.any { glyph.textStart < it.end && glyph.textEnd > it.start }
        if (kinds.lastOrNull()!=isMath) {
            kinds += isMath
            groups.add(mutableListOf())
        }
        groups.last() += glyph
    }
    return groups.mapIndexed { index, glyphs ->
        val mathRun=kinds[index]
        val text=buildString {
            glyphs.forEachIndexed { glyphIndex,glyph ->
                if (glyphIndex>0 && glyph.inferredSpaceBefore) append(' ')
                append(glyph.text)
            }
        }
        PdfTextRun(mathRun,glyphs,text)
    }
}

private val PDF_MATH_RUN = Regex(
    "(?<![\\p{L}\\p{N}])(?:(?:\\p{L}\\d*|\\$?\\d+\\p{L}?)(?:\\s*[=×*+−/<>-]\\s*(?:\\p{L}\\d+|\\p{L}|\\$?\\d+\\p{L}?))+|[=×*+−/<>-]+)(?![\\p{L}\\p{N}])"
)

private fun isProseDash(text: String, match: MatchResult): Boolean {
    if ('-' !in match.value || match.value.any { it in "=×*+−/<>" } || match.value.count { it == '-' } != 1) return false
    val left = text.substring(0, match.range.first).trimEnd().takeLastWhile(Char::isLetter)
    val right = text.substring(match.range.last + 1).trimStart().takeWhile(Char::isLetter)
    return left.length > 1 && right.length > 1
}

internal fun splitPdfText(text: String): List<PdfTextSegment> {
    val result = mutableListOf<PdfTextSegment>()
    var start = 0
    PDF_MATH_RUN.findAll(text).forEach { match ->
        if (!isProseDash(text, match)) {
            var first=match.range.first
            var end=match.range.last+1
            if (match.value.all { it in "=×*+−/<>-" }) {
                while (first>start && text[first-1].isWhitespace()) first--
                while (end<text.length && text[end].isWhitespace()) end++
            }
            if (first > start)
                result += PdfTextSegment(text.substring(start,first), false,start,first)
            result += PdfTextSegment(text.substring(first,end),true,first,end)
            start=end
        }
    }
    if (start < text.length) result += PdfTextSegment(text.substring(start), false, start, text.length)
    return result.ifEmpty { listOf(PdfTextSegment(text, false, 0, text.length)) }
}

internal fun hasSeparablePdfMath(text: String): Boolean {
    val segments = splitPdfText(text)
    return segments.any { it.isMathematics } &&
        segments.any { !it.isMathematics && it.text.any(Char::isLetter) }
}

private val PDF_CITATION_AUTHOR = """\p{Lu}[\p{L}\p{M}\p{N}&.'’\-]*(?:\s+et\s+al\.)?(?:\s+&\s+\p{Lu}[\p{L}\p{M}\p{N}&.'’\-]*)*"""
private val PDF_CITATION_YEAR = """(?:19|20)\d{2}[a-z]?"""
private val PDF_CITATION = Regex(
    """\((?:$PDF_CITATION_AUTHOR,\s*$PDF_CITATION_YEAR\s*;?\s*)+\)|$PDF_CITATION_AUTHOR,\s*$PDF_CITATION_YEAR|$PDF_CITATION_AUTHOR\s*\(\s*$PDF_CITATION_YEAR\s*\)"""
)

private fun pdfCitationGlyphs(container: PdfContainer): Set<Pair<PdfShow,Int>> {
    val rows=mutableListOf<MutableList<PdfShow>>()
    container.shows.filter { it.visible && it.firstMatrix!=null && it.glyphs.isNotEmpty() }.forEach { show ->
        val y=show.firstMatrix!!.translateY
        val row=rows.firstOrNull { abs(it.first().firstMatrix!!.translateY-y)<=show.state.textState.fontSize*.25f }
        (row ?: mutableListOf<PdfShow>().also(rows::add)).add(show)
    }
    val protected=mutableSetOf<Pair<PdfShow,Int>>()
    rows.forEach { row ->
        var text=StringBuilder()
        var refs=mutableListOf<Pair<PdfShow,Int>?>()
        fun collect() {
            PDF_CITATION.findAll(text).forEach { match ->
                match.range.forEach { offset -> refs.getOrNull(offset)?.let(protected::add) }
            }
            text=StringBuilder()
            refs=mutableListOf()
        }
        var previous:PdfShow?=null
        row.sortedBy { it.firstMatrix!!.translateX }.forEach { show ->
            val prior=previous
            if(prior!=null) {
                val advance=prior.glyphs.sumOf { it.advance.toDouble() }.toFloat()*
                    prior.state.textState.horizontalScaling/100f
                val gap=show.firstMatrix!!.translateX-prior.firstMatrix!!.translateX-advance
                if(gap>prior.state.textState.fontSize*1.5f) collect()
                else if(gap>maxOf(prior.state.textState.fontSize*.15f,
                        prior.state.textState.font!!.spaceWidth*prior.state.textState.fontSize/1000f*.45f)) {
                    text.append(' ')
                    refs+=null
                }
            }
            var glyphIndex=0
            show.text.forEachIndexed { offset,char ->
                while(glyphIndex<show.glyphs.size && offset>=show.glyphs[glyphIndex].textEnd) glyphIndex++
                val glyph=show.glyphs.getOrNull(glyphIndex)
                text.append(char)
                refs+=if(glyph!=null && offset>=glyph.textStart) show to glyphIndex else null
            }
            previous=show
        }
        collect()
    }
    return protected
}
internal data class PdfGlyphRef(val show: PdfShow, val glyph: PdfGlyph, val index: Int)
internal interface PdfProtectedAnchor {
    val text: String
    val glyphs: List<PdfGlyphRef>
    val sourceStyle: PdfShow
    val bounds: RectF
    val pageBounds: RectF
}

internal sealed interface PdfParagraphPart {
    val text: String
    val glyphs: List<PdfGlyphRef>
    val sourceStyle: PdfShow

    data class Text(
        override val text: String,
        override val glyphs: List<PdfGlyphRef>,
        override val sourceStyle: PdfShow
    ) : PdfParagraphPart

    data class MathAnchor(
        val id: Int,
        override val text: String,
        override val glyphs: List<PdfGlyphRef>,
        override val sourceStyle: PdfShow,
        override val bounds: RectF,
        override val pageBounds: RectF
    ) : PdfParagraphPart, PdfProtectedAnchor
    data class CitationAnchor(
        val id: Int,
        override val text: String,
        override val glyphs: List<PdfGlyphRef>,
        override val sourceStyle: PdfShow,
        override val bounds: RectF,
        override val pageBounds: RectF
    ) : PdfParagraphPart, PdfProtectedAnchor
}

private fun sameParagraphStyle(first: PdfShow, other: PdfShow): Boolean {
    val a=first.state.textState; val b=other.state.textState
    return a.font == b.font && a.fontSize == b.fontSize && a.rise == b.rise &&
        first.renderingMode == other.renderingMode &&
        first.state.nonStrokingColor == other.state.nonStrokingColor &&
        (first.renderingMode == 0 || (first.state.strokingColor == other.state.strokingColor &&
            first.state.lineWidth == other.state.lineWidth && first.state.lineCap == other.state.lineCap &&
            first.state.lineJoin == other.state.lineJoin && first.state.miterLimit == other.state.miterLimit &&
            first.state.lineDashPattern == other.state.lineDashPattern))
}

internal class PdfParagraph(val shows: MutableList<PdfShow>) {
    val first get() = shows.minBy { it.operation.index }
    val basis: Matrix = first.firstMatrix!!.clone()
    val inverse = pdfAndroidMatrix(basis).let { matrix -> android.graphics.Matrix().also {
        if (!matrix.invert(it)) rejectPdf("Singular paragraph geometry", first.location)
    } }
    fun relative(show: PdfShow): RectF = RectF(show.ink).also { inverse.mapRect(it) }
    fun baseline(show: PdfShow): FloatArray = floatArrayOf(show.firstMatrix!!.translateX, show.firstMatrix!!.translateY).also { inverse.mapPoints(it) }
    val size get() = first.state.textState.fontSize
    val box: RectF get() = RectF().also { result -> shows.forEach { result.union(relative(it)) } }
    val parts: List<PdfParagraphPart> by lazy(::buildParts)
    val text: String get() = parts.joinToString("") { it.text }
    val hasProse get() = parts.any { it is PdfParagraphPart.Text && it.text.any(Char::isLetter) }

    private fun buildParts(): List<PdfParagraphPart> {
        val text = StringBuilder()
        val refs = mutableListOf<PdfGlyphRef?>()
        val styles = mutableListOf<PdfShow?>()
        var previous: PdfGlyphRef? = null
        shows.sortedBy { it.operation.index }.forEach { show ->
            show.glyphs.forEachIndexed { index, glyph ->
                val ref = PdfGlyphRef(show, glyph,index)
                val old = previous
                if (old != null) {
                    val lineBreak = abs(baseline(old.show)[1] - baseline(show)[1]) > size * .25f
                    val joinsHyphenatedWord = lineBreak && text.lastOrNull() == '-' &&
                        glyph.text.firstOrNull()?.isLowerCase() == true
                    if (joinsHyphenatedWord) {
                        text.deleteCharAt(text.lastIndex)
                        refs.removeAt(refs.lastIndex)
                        styles.removeAt(styles.lastIndex)
                    } else {
                        val inferredGap = if (index > 0) glyph.inferredSpaceBefore else {
                            val inverse = android.graphics.Matrix()
                            if (!pdfAndroidMatrix(old.glyph.matrix).invert(inverse))
                                rejectPdf("Ambiguous glyph spacing", show.location)
                            val nextPosition = floatArrayOf(glyph.matrix.translateX, glyph.matrix.translateY)
                            inverse.mapPoints(nextPosition)
                            val textSize = old.show.state.textState.fontSize
                            val wordGap = maxOf(textSize * .15f,
                                old.show.state.textState.font!!.spaceWidth * textSize / 1000f * .45f)
                            abs(nextPosition[1]) > textSize * .25f ||
                                nextPosition[0] - old.glyph.advance - wordGap > 0f
                        }
                        if ((lineBreak || inferredGap) && text.isNotEmpty() && !text.last().isWhitespace() &&
                            glyph.text.firstOrNull()?.isWhitespace() != true) {
                            text.append(' ')
                            refs += null
                            styles += show
                        }
                    }
                } else if (index > 0 && glyph.inferredSpaceBefore) {
                    text.append(' ')
                    refs += null
                    styles += show
                }
                val start = text.length
                text.append(glyph.text)
                repeat(glyph.text.length) {
                    refs += ref
                    styles += show
                }
                previous = ref
            }
        }
        val result = mutableListOf<PdfParagraphPart>()
        fun appendAnchor(segmentText:String,start:Int,end:Int,citation:Boolean) {
            val glyphs=refs.subList(start,end).filterNotNull().distinctBy { it.show to it.glyph }
            val pageBounds=RectF()
            glyphs.forEach { pageBounds.union(it.glyph.pageInk) }
            val pageToParagraph=android.graphics.Matrix()
            if(!pdfAndroidMatrix(basis.multiply(first.state.currentTransformationMatrix)).invert(pageToParagraph))
                rejectPdf("Ambiguous protected anchor geometry",first.location)
            val bounds=RectF(pageBounds)
            pageToParagraph.mapRect(bounds)
            val style=glyphs.firstOrNull()?.show ?: first
            if(citation) result+=PdfParagraphPart.CitationAnchor(
                result.count { it is PdfParagraphPart.CitationAnchor },segmentText,glyphs,style,bounds,pageBounds)
            else result+=PdfParagraphPart.MathAnchor(
                result.count { it is PdfParagraphPart.MathAnchor },segmentText,glyphs,style,bounds,pageBounds)
        }
        fun isCitationAt(index:Int):Boolean {
            val ref=refs.getOrNull(index) ?: return false
            return ref.show.container.citationGlyphs.contains(ref.show to ref.index)
        }
        var segmentStart=0
        while(segmentStart<text.length) {
            if(isCitationAt(segmentStart)) {
                var end=segmentStart+1
                while(end<text.length && isCitationAt(end)) end++
                appendAnchor(text.substring(segmentStart,end),segmentStart,end,true)
                segmentStart=end
            } else {
                var end=segmentStart+1
                while(end<text.length && !isCitationAt(end)) end++
                splitPdfText(text.substring(segmentStart,end)).forEach { segment ->
                    val start=segmentStart+segment.start
                    val stop=segmentStart+segment.end
                    if(segment.isMathematics) appendAnchor(text.substring(start,stop),start,stop,false)
                    else {
                        var index=start
                        while(index<stop) {
                            val style=styles.getOrNull(index) ?: first
                            var textEnd=index+1
                            while(textEnd<stop && styles.getOrNull(textEnd)?.let { sameParagraphStyle(style,it) }==true) textEnd++
                            val glyphs=refs.subList(index,textEnd).filterNotNull().distinctBy { it.show to it.glyph }
                            result+=PdfParagraphPart.Text(text.substring(index,textEnd),glyphs,style)
                            index=textEnd
                        }
                    }
                }
                segmentStart=end
            }
        }
        return result
    }
}

internal fun allPdfShows(root: PdfContainer): List<PdfShow> = buildList {
    addAll(root.shows)
    root.children.values.forEach { addAll(allPdfShows(it)) }
}

private fun separatedByPdfRule(paragraph: PdfParagraph, previous: PdfShow, next: PdfShow, rules: List<PdfRule>): Boolean {
    if (rules.isEmpty()) return false
    val inverse=android.graphics.Matrix()
    if (!pdfAndroidMatrix(paragraph.basis.multiply(paragraph.first.state.currentTransformationMatrix)).invert(inverse))
        rejectPdf("Ambiguous rule geometry",paragraph.first.location)
    val a=paragraph.baseline(previous); val b=paragraph.baseline(next)
    val old=paragraph.relative(previous); val new=paragraph.relative(next)
    return rules.any { rule ->
        val endpoints=floatArrayOf(rule.from.x,rule.from.y,rule.to.x,rule.to.y)
        inverse.mapPoints(endpoints)
        val x1=endpoints[0]; val y1=endpoints[1]; val x2=endpoints[2]; val y2=endpoints[3]
        val horizontal=abs(y1-y2)<.1f && y1>minOf(a[1],b[1])+.1f && y1<maxOf(a[1],b[1])-.1f &&
            maxOf(x1,x2)>maxOf(old.left,new.left) && minOf(x1,x2)<minOf(old.right,new.right)
        val vertical=abs(x1-x2)<.1f && x1>minOf(old.centerX(),new.centerX()) &&
            x1<maxOf(old.centerX(),new.centerX()) && maxOf(y1,y2)>=minOf(a[1],b[1]) &&
            minOf(y1,y2)<=maxOf(a[1],b[1])
        horizontal || vertical
    }
}
private val PDF_EPOCH_GROUPING_BARRIERS = setOf("BMC", "BDC", "DP", "EMC", "Do", "BI", "sh", "W", "W*")

private fun canJoinPdfEpochs(root: PdfContainer, paragraph: PdfParagraph, show: PdfShow): Boolean {
    val previous = paragraph.shows.last()
    if (previous.epoch == show.epoch) return true
    return root.operations.none { operation ->
        operation.index > previous.operation.index && operation.index < show.operation.index &&
            operation.operator.name in PDF_EPOCH_GROUPING_BARRIERS
    }
}

internal fun groupPdfParagraphs(root: PdfContainer, rules: List<PdfRule> = root.rules): List<PdfParagraph> {
    root.citationGlyphs=pdfCitationGlyphs(root)
    val result = mutableListOf<PdfParagraph>()
    val eligible = root.shows.filter { show ->
        show.visible && show.text.isNotBlank() &&
            (linguisticPdfText(show.text.toString(), show.state.textState.font) ||
                splitPdfText(show.text.toString()).any { it.isMathematics })
    }
    eligible.forEach { show ->
        val showHasProse = linguisticPdfText(show.text.toString(), show.state.textState.font)
        val candidates = result.filter { paragraph ->
            val paragraphHasProse = paragraph.shows.any {
                linguisticPdfText(it.text.toString(), it.state.textState.font)
            }
            if (!showHasProse && !paragraphHasProse) return@filter false
            val first = paragraph.first
            val showMatrix=show.firstMatrix!!
            val firstMatrix=first.firstMatrix!!
            if (!canJoinPdfEpochs(root, paragraph, show) ||
                showMatrix.scaleY != firstMatrix.scaleY ||
                showMatrix.shearX != firstMatrix.shearX || showMatrix.shearY != firstMatrix.shearY ||
                show.state.currentTransformationMatrix != first.state.currentTransformationMatrix) false
            else {
                val p = paragraph.baseline(show)
                val last = paragraph.shows.last()
                val q = paragraph.baseline(last)
                val r = paragraph.relative(show); val old = paragraph.relative(last)
                val sameLine = abs(p[1]-q[1]) <= paragraph.size*.25f &&
                    r.left >= old.right-paragraph.size*.1f && r.left-old.right <= paragraph.size*1.2f
                val previousLine = RectF().also { line ->
                    paragraph.shows.filter { abs(paragraph.baseline(it)[1]-q[1]) <= paragraph.size*.25f }
                        .forEach { line.union(paragraph.relative(it)) }
                }
                val alignedStart = abs(p[0]) <= paragraph.size*.75f
                val alignedCenter = abs(r.centerX()-previousLine.centerX()) <= paragraph.size*.75f
                val nextLine = showHasProse && paragraphHasProse &&
                    p[1] < q[1]-paragraph.size*.5f && q[1]-p[1] <= paragraph.size*1.8f &&
                    (alignedStart || alignedCenter)
                val decorationBetween = root.shows.any { other ->
                    other.visible && other.operation.index > last.operation.index && other.operation.index < show.operation.index &&
                        !linguisticPdfText(other.text.toString(),other.state.textState.font) &&
                        other !in paragraph.shows && !splitPdfText(other.text.toString()).any { it.isMathematics }
                }
                (sameLine || nextLine) && !decorationBetween && !separatedByPdfRule(paragraph,last,show,rules)
            }
        }
        val resolvedCandidates=if(candidates.size<=1) candidates else candidates.filter { paragraph ->
            val previous=paragraph.shows.last()
            val p=paragraph.baseline(show); val q=paragraph.baseline(previous)
            val r=paragraph.relative(show); val old=paragraph.relative(previous)
            abs(p[1]-q[1])<=paragraph.size*.25f &&
                r.left>=old.right-paragraph.size*.1f && r.left-old.right<=paragraph.size*1.2f
        }
        if(resolvedCandidates.size>1) rejectPdf("Ambiguous paragraph or column association",show.location)
        if(resolvedCandidates.isEmpty()) result+=PdfParagraph(mutableListOf(show))
        else resolvedCandidates.single().shows+=show
    }
    result.forEach { paragraph ->
        paragraph.shows.forEach { show ->
            val a = paragraph.basis; val b = show.firstMatrix!!
            if (a.scaleY != b.scaleY || a.shearX != b.shearX || a.shearY != b.shearY)
                rejectPdf("Changing paragraph orientation or scale", show.location)
        }
        // Composition retains the complete source artwork, so overlapping source shows and anchors
        // are retained rather than rejected for same-position replacement.
    }
    root.children.values.forEach { result += groupPdfParagraphs(it,rules) }
    return result
}
internal sealed interface PdfTranslatedPart {
    data class Text(val text: String, val sourceStyle: PdfShow) : PdfTranslatedPart
    data class Protected(val anchor: PdfProtectedAnchor) : PdfTranslatedPart
}

/** Ordered prose chunks are coalesced across PDF operator/style fragments; protected anchors stay local. */
internal fun PdfParagraph.translationParts(): List<PdfTranslatedPart> {
    val baseStyle = parts.filterIsInstance<PdfParagraphPart.Text>().firstOrNull()?.sourceStyle ?: first
    val result = mutableListOf<PdfTranslatedPart>()
    var prose = StringBuilder()
    fun flush() {
        if (prose.isNotEmpty()) result += PdfTranslatedPart.Text(prose.toString(), baseStyle)
        prose = StringBuilder()
    }
    parts.forEach { part ->
        when (part) {
            is PdfParagraphPart.Text -> prose.append(part.text)
            is PdfParagraphPart.MathAnchor -> {
                flush()
                result += PdfTranslatedPart.Protected(part)
            }
            is PdfParagraphPart.CitationAnchor -> {
                flush()
                result += PdfTranslatedPart.Protected(part)
            }
        }
    }
    flush()
    return result
}

internal fun requireSimplePdfScript(text: String, location: String) {
    text.codePoints().forEach { code ->
        val type = Character.getType(code)
        val script = Character.UnicodeScript.of(code)
        if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() || code == 0x200d || code == 0x200c ||
            script !in PDF_SIMPLE_SCRIPTS)
            rejectPdf("The text requires unsupported shaping or script-specific layout", location)
        if (Character.isISOControl(code) && code != 10 && code != 13 && code != 9)
            rejectPdf("The translation contains non-printing controls", location)
    }
}

internal class PdfTranslationFonts(private val context: Context, private val document: PDDocument) {
    private val loaded = mutableMapOf<String, PDType0Font>()
    fun choose(original: PDFont, text: String, location: String): PDFont {
        requireSimplePdfScript(text, location)
        fun usable(font: PDFont): Boolean = try {
            val plain = text.replace("\n", "").replace("\r", "").replace('\t',' ')
            val bytes = font.encode(plain)
            val input = ByteArrayInputStream(bytes)
            val decoded = StringBuilder()
            while (input.available() > 0) {
                val code = font.readCode(input)
                pdfGlyphPath(font, code, location)
                decoded.append(font.toUnicode(code) ?: "\ufffd")
            }
            font.willBeSubset() || decoded.toString() == plain
        } catch (_: IllegalArgumentException) { false } catch (_: java.io.IOException) { false }
        // Never ask a source subset font to encode new text or subset again; use the style-matched bundled font.
        if (!original.willBeSubset() && usable(original)) return original
        val descriptor = original.fontDescriptor
        val name = original.name.lowercase()
        val bold = descriptor?.isForceBold == true || (descriptor?.fontWeight ?: 0f) >= 600f || name.contains("bold")
        val italic = descriptor?.isItalic == true || name.contains("italic") || name.contains("oblique")
        val family = if (descriptor?.isSerif == true || name.contains("times") || name.contains("serif") || name.contains("nimbusrom")) "LiberationSerif" else "LiberationSans"
        val style = when { bold && italic -> "BoldItalic"; bold -> "Bold"; italic -> "Italic"; else -> "Regular" }
        val cjk = text.any { it in '\u2e80'..'\u9fff' || it in '\uf900'..'\ufaff' }
        val asset = if (cjk) "DroidSansFallback.ttf" else "$family-$style.ttf"
        // Droid is regular-only; never fabricate bold/italic using horizontal distortion or duplicate ink.
        if (cjk && (bold || italic)) rejectPdf("Bundled CJK font has no matching bold or italic style", location)
        val font = loaded.getOrPut(asset) { context.assets.open("pdf-fonts/$asset").use { PDType0Font.load(document,it,true) } }
        if (!usable(font)) rejectPdf("No bundled font covers every translated glyph", location)
        return font
    }
}
