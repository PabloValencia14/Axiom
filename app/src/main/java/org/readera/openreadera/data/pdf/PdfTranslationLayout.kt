package org.readera.openreadera.data.pdf

import android.content.Context
import android.graphics.Path
import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.util.Matrix
import org.readera.openreadera.translation.TranslationFitPolicy
import java.io.ByteArrayInputStream
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs

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
    val text: String get() {
        val result = StringBuilder()
        var previous: PdfShow? = null
        shows.forEach { show ->
            previous?.let { old ->
                val a = baseline(old); val b = baseline(show)
                val gap = relative(show).left - relative(old).right
                if (abs(a[1]-b[1]) > size * .25f || gap > size * .20f) {
                    if (result.isNotEmpty() && !result.last().isWhitespace() && !show.text.first().isWhitespace()) result.append(' ')
                }
            }
            result.append(show.text)
            previous = show
        }
        return result.toString().trim()
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

internal fun groupPdfParagraphs(root: PdfContainer, rules: List<PdfRule> = root.rules): List<PdfParagraph> {
    val result = mutableListOf<PdfParagraph>()
    root.shows.filter { it.visible && PDF_SINGLE_LETTER.matches(it.text.toString().trim()) }.forEach { single ->
        root.shows.filter { it.visible && it.epoch==single.epoch &&
            linguisticPdfText(it.text.toString(),it.state.textState.font) }.forEach { prose ->
            val geometry=PdfParagraph(mutableListOf(prose))
            val a=geometry.baseline(single); val b=geometry.baseline(prose)
            val ink=geometry.relative(single); val other=geometry.relative(prose)
            val gap=maxOf(ink.left-other.right,other.left-ink.right,0f)
            if (abs(a[1]-b[1]) <= geometry.size*.25f && gap <= geometry.size*.8f)
                rejectPdf("A single-letter operator beside prose is ambiguous between split text and a mathematical token",single.location)
        }
    }
    // Work within each invocation and paint epoch. Baseline/column adjacency, never a page-global sort.
    root.shows.filter { it.visible && linguisticPdfText(it.text.toString(), it.state.textState.font) }.forEach { show ->
        val candidates = result.filter { paragraph ->
            val first = paragraph.first
            val ts = show.state.textState; val fs = first.state.textState
            if (show.epoch != first.epoch || ts.font != fs.font || ts.fontSize != fs.fontSize || ts.rise != fs.rise ||
                show.state.currentTransformationMatrix != first.state.currentTransformationMatrix ||
                show.state.nonStrokingColor != first.state.nonStrokingColor) false
            else {
                val p = paragraph.baseline(show)
                val last = paragraph.shows.last()
                val q = paragraph.baseline(last)
                val r = paragraph.relative(show); val old = paragraph.relative(last)
                val sameLine = abs(p[1]-q[1]) <= paragraph.size*.25f && r.left >= old.right-paragraph.size*.1f && r.left-old.right <= paragraph.size*.8f
                val nextLine = p[1] < q[1]-paragraph.size*.5f && q[1]-p[1] <= paragraph.size*1.8f && abs(p[0]) <= paragraph.size*.75f
                val decorationBetween = root.shows.any { other ->
                    other.visible && other.operation.index > last.operation.index && other.operation.index < show.operation.index &&
                        !linguisticPdfText(other.text.toString(),other.state.textState.font)
                }
                (sameLine || nextLine) && !decorationBetween && !separatedByPdfRule(paragraph,last,show,rules)
            }
        }
        if (candidates.size > 1) rejectPdf("Ambiguous paragraph or column association", show.location)
        if (candidates.isEmpty()) result += PdfParagraph(mutableListOf(show)) else candidates.single().shows += show
    }
    result.forEach { paragraph ->
        paragraph.shows.forEach { show ->
            val a = paragraph.basis; val b = show.firstMatrix!!
            if (a.scaleX != b.scaleX || a.scaleY != b.scaleY || a.shearX != b.shearX || a.shearY != b.shearY)
                rejectPdf("Changing paragraph orientation or scale", show.location)
        }
        val indices = paragraph.shows.map { it.operation.index }
        val intervening = root.shows.filter { it.operation.index in indices.min()..indices.max() && it !in paragraph.shows }
        if (intervening.any { it.visible && !it.ink.isEmpty && RectF.intersects(paragraph.box, paragraph.relative(it)) })
            rejectPdf("Paragraph crosses mathematical or differently styled text", paragraph.first.location)
    }
    root.children.values.forEach { result += groupPdfParagraphs(it,rules) }
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
        if (usable(original)) return original
        val descriptor = original.fontDescriptor
        val name = original.name.lowercase()
        val bold = descriptor?.isForceBold == true || (descriptor?.fontWeight ?: 0f) >= 600f || name.contains("bold")
        val italic = descriptor?.isItalic == true || name.contains("italic") || name.contains("oblique")
        val family = if (descriptor?.isSerif == true || name.contains("times") || name.contains("serif")) "LiberationSerif" else "LiberationSans"
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

internal data class PdfFittedParagraph(
    val paragraph: PdfParagraph,
    val font: PDFont,
    val size: Float,
    val leading: Float,
    val lines: List<String>,
    val ink: List<RectF>
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

internal fun fitPdfParagraph(paragraph: PdfParagraph, translation: String, font: PDFont, policy: TranslationFitPolicy): PdfFittedParagraph {
    val location = paragraph.first.location
    if (translation.isBlank()) rejectPdf("The provider returned an empty translation", location)
    val box = paragraph.box
    val originalSize = paragraph.size
    val baselines = paragraph.shows.map { paragraph.baseline(it)[1] }.distinct().sortedDescending()
    val originalLeading = if (baselines.size > 1) baselines.zipWithNext().minOf { (a,b) -> a-b } else originalSize*1.2f
    // The inferred container is the original text envelope plus a small font-bearing allowance, not the column/page.
    val envelope = RectF(box).apply { inset(-originalSize*.20f,-originalSize*.20f) }
    val width = box.right.coerceAtLeast(originalSize)
    val minimum = maxOf(originalSize*policy.minFontScale, minOf(originalSize,policy.minimumFontPt))
    val normalized = translation.replace("\r\n","\n").replace('\r','\n').replace('\t',' ')
    val breaker = BreakIterator.getLineInstance(Locale.ROOT)
    fun wrap(size: Float): List<String>? {
        val lines = mutableListOf<String>()
        for (hardLine in normalized.split('\n')) {
            breaker.setText(hardLine)
            var start = breaker.first(); var end = breaker.next(); var current = ""
            while (end != BreakIterator.DONE) {
                val chunk = hardLine.substring(start,end)
                val candidate = current + chunk
                if (font.getStringWidth(candidate.trimEnd())*size/1000f <= width) current=candidate
                else {
                    if (current.isBlank()) return null
                    lines += current.trimEnd(); current=chunk.trimStart()
                    if (font.getStringWidth(current.trimEnd())*size/1000f > width) return null
                }
                start=end; end=breaker.next()
            }
            lines += current.trimEnd()
        }
        return lines
    }
    for (step in 0..30) {
        val size = originalSize-(originalSize-minimum)*step/30f
        val lines = wrap(size) ?: continue
        for (leadingStep in 0..10) {
            val leading = originalLeading*(1f-(1f-policy.minLeadingScale)*leadingStep/10f)
            if (lines.size>1 && leading < size*1.05f) continue
            val inks = lines.mapIndexed { index,line -> pdfTextInk(font,line,size,location).apply { offset(0f,-index*leading+paragraph.first.state.textState.rise) } }
            if (inks.filterNot { it.isEmpty }.any { !envelope.contains(it) }) continue
            if (inks.zipWithNext().any { (a,b) -> RectF.intersects(a,b) }) continue
            return PdfFittedParagraph(paragraph,font,size,leading,lines,inks)
        }
    }
    rejectPdf("The complete translation does not fit at the permitted font size and leading", location)
}
