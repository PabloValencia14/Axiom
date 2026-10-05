package org.readera.openreadera.data.pdf

import android.graphics.Path
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Region
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.*
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDTransparencyGroup
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import org.readera.openreadera.translation.TranslationRejectedException
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

internal val PDF_SIMPLE_SCRIPTS = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.CYRILLIC,
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.COMMON)
internal val PDF_SINGLE_LETTER = Regex("^[\\p{IsLatin}\\p{IsCyrillic}]$")
private val PDF_LATIN_PROSE = Regex("[\\p{IsLatin}\\p{IsCyrillic}]{2,}")
private val PDF_LINGUISTIC_TEXT = Regex("[\\p{IsLatin}\\p{IsCyrillic}]{2,}|[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]")

internal fun rejectPdf(reason: String, location: String): Nothing =
    throw TranslationRejectedException(reason, location)

internal data class PdfOperation(val index: Int, val operands: List<COSBase>, val operator: Operator)
internal class PdfContainer(
    val id: String,
    val operations: List<PdfOperation>,
    val resources: PDResources,
    val page: PDPage? = null,
    val form: PDFormXObject? = null
) {
    val shows = mutableListOf<PdfShow>()
    val children = linkedMapOf<Int, PdfContainer>()
    var cursor = 0
    var depth = 0
    var epoch = 0
    var inText = false
    var active: PdfShow? = null
    var clipped = false
    val clipStack = mutableListOf<Boolean>()
    var markedDepth = 0
    val graphics = mutableListOf<PdfGraphic>()
    val rules = mutableListOf<PdfRule>()
}
internal class PdfShow(
    val container: PdfContainer,
    val operation: PdfOperation,
    var before: Matrix,
    var lineBefore: Matrix,
    var state: PDGraphicsState,
    val epoch: Int,
    val paintOrder: Int
) {
    val text = StringBuilder()
    val ink = RectF()
    val pageInk = RectF()
    var lineAtShow: Matrix = lineBefore.clone()
    var firstMatrix: Matrix? = null
    var previousGlyph: Matrix? = null
    var previousAdvance = 0f
    var visible = false
    val location get() = "${container.id}/operator ${operation.index}"
}

internal data class PdfGraphic(val bounds: RectF, val region: Region, val paintOrder: Int)
internal data class PdfRule(val from: PointF, val to: PointF)

/** Inspect alternate-text and optional-content channels without walking page/parent back references. */
internal fun requireSafePdfMetadata(value: COSBase?, location: String) {
    val visited = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
    fun inspect(item: COSBase?) {
        if (item == null || !visited.add(item)) return
        when (item) {
            is COSObject -> inspect(item.`object`)
            is COSArray -> item.forEach(::inspect)
            is COSDictionary -> {
                if (item.containsKey(COSName.ACTUAL_TEXT) || item.containsKey(COSName.ALT) ||
                    item.containsKey(COSName.OC) || item.containsKey(COSName.OCPROPERTIES) ||
                    item.getNameAsString(COSName.TYPE) in setOf("OCG","OCMD"))
                    rejectPdf("Alternate text or optional-content channels are unsupported",location)
                item.keySet().forEach { key -> if (key != COSName.P && key != COSName.PG) inspect(item.getItem(key)) }
            }
        }
    }
    inspect(value)
}

internal fun parsePdfOperations(parser: PDFStreamParser): List<PdfOperation> {
    parser.parse()
    val result = mutableListOf<PdfOperation>()
    val operands = mutableListOf<COSBase>()
    parser.tokens.forEach { token ->
        when (token) {
            is COSBase -> operands += token
            is Operator -> {
                result += PdfOperation(result.size, operands.toList(), token)
                operands.clear()
            }
            else -> throw IOException("Unexpected PDF content token")
        }
    }
    if (operands.isNotEmpty()) throw IOException("Unterminated PDF operands")
    return result
}

internal fun copyPdfResources(source: PDResources?): PDResources {
    val dictionary = COSDictionary(source?.cosObject ?: COSDictionary())
    // Category dictionaries must not be shared when adding fonts or context-specific forms.
    dictionary.keySet().toList().forEach { key ->
        val value = dictionary.getDictionaryObject(key)
        if (value is COSDictionary) dictionary.setItem(key, COSDictionary(value))
    }
    return PDResources(dictionary)
}

internal fun pdfAndroidMatrix(matrix: Matrix): android.graphics.Matrix = android.graphics.Matrix().apply {
    setValues(floatArrayOf(matrix.scaleX, matrix.shearX, matrix.translateX,
        matrix.shearY, matrix.scaleY, matrix.translateY, 0f, 0f, 1f))
}

internal fun pdfGlyphPath(font: PDFont, code: Int, location: String): Path {
    val path = when (font) {
        is PDVectorFont -> {
            if (!font.hasGlyph(code)) rejectPdf("The font has a missing glyph", location)
            Path(font.getPath(code))
        }
        is PDSimpleFont -> {
            val name = font.encoding.getName(code)
            if (!font.hasGlyph(name)) rejectPdf("The font has a missing glyph", location)
            Path(font.getPath(name))
        }
        else -> rejectPdf("Font outlines cannot be measured safely", location)
    }
    val trueType = when (font) {
        is PDTrueTypeFont -> font.trueTypeFont
        is PDType0Font -> (font.descendantFont as? PDCIDFontType2)?.trueTypeFont
        else -> null
    }
    if (trueType != null) {
        val scale = 1000f / trueType.unitsPerEm
        path.transform(pdfAndroidMatrix(Matrix.getScaleInstance(scale, scale)))
    }
    return path
}

internal fun orthogonalPdfMatrix(matrix: Matrix): Boolean {
    val a = matrix.scaleX; val b = matrix.shearY
    val c = matrix.shearX; val d = matrix.scaleY
    return a.isFinite() && b.isFinite() && c.isFinite() && d.isFinite() &&
        matrix.translateX.isFinite() && matrix.translateY.isFinite() &&
        abs(a*d-b*c) > 0.00001f &&
        ((abs(b)<0.0001f && abs(c)<0.0001f) || (abs(a)<0.0001f && abs(d)<0.0001f))
}

internal fun linguisticPdfText(text: String, font: PDFont): Boolean {
    val name = font.name.lowercase()
    if (name.contains("symbol") || name.contains("dingbat") || name.contains("math")) return false
    if (text.any { it in '\u2200'..'\u22ff' || it in '\u0370'..'\u03ff' || it == '=' }) return false
    return PDF_LINGUISTIC_TEXT.containsMatchIn(text)
}

internal class PdfTranslationAnalyzer(page: PDPage, pageIndex: Int) : PDFGraphicsStreamEngine(page) {
    val root = PdfContainer("page ${pageIndex + 1}", parsePdfOperations(PDFStreamParser(page)),
        copyPdfResources(page.resources), page = page)
    private var container = root
    private val forms = mutableSetOf<COSStream>()
    private val path = Path()
    private var point = PointF()
    private var paintOrder = 0
    private val pendingRules = mutableListOf<PdfRule>()
    var invisibleText = false
        private set

    override fun processOperator(operator: Operator, operands: MutableList<COSBase>?) {
        val context = container
        if (context.depth++ > 0) {
            try { super.processOperator(operator, operands ?: mutableListOf()) } finally { context.depth-- }
            return
        }
        val args = operands ?: rejectPdf("Content operator has no operands", context.id)
        val operation = context.operations.getOrNull(context.cursor++)
            ?: rejectPdf("Content operator identity is ambiguous", context.id)
        if (operation.operator.name != operator.name) rejectPdf("Content operator identity changed", context.id)
        val name = operator.name
        try {
            when (name) {
                "BT" -> {
                    if (context.inText) rejectPdf("Nested text objects", context.id)
                    context.inText = true
                }
                "ET" -> {
                    if (!context.inText) rejectPdf("Unbalanced text objects", context.id)
                    context.inText = false
                }
                "q" -> context.clipStack += context.clipped
                "Q" -> {
                    if (context.clipStack.isEmpty()) rejectPdf("Unbalanced graphics state", context.id)
                    context.clipped = context.clipStack.removeAt(context.clipStack.lastIndex)
                }
                "W", "W*" -> context.clipped = true
                "BDC", "DP" -> {
                    if (operands.size != 2 || operands[0] !is COSName)
                        rejectPdf("Malformed marked-content properties",context.id)
                    if ((operands[0] as COSName).name == "OC")
                        rejectPdf("Optional-content marked text is unsupported",context.id)
                    val properties = when (val property = operands[1]) {
                        is COSDictionary -> property
                        is COSName -> resources.getProperties(property)?.cosObject
                        else -> null
                    } ?: rejectPdf("Unresolved marked-content properties",context.id)
                    requireSafePdfMetadata(properties,context.id)
                    context.epoch++
                    if (name == "BDC") context.markedDepth++
                }
                "BMC" -> {
                    if (operands.size != 1 || operands[0] !is COSName || (operands[0] as COSName).name == "OC")
                        rejectPdf("Malformed or optional marked-content scope",context.id)
                    context.epoch++; context.markedDepth++
                }
                "EMC" -> {
                    if (context.markedDepth == 0) rejectPdf("Unbalanced marked-content scope",context.id)
                    context.markedDepth--; context.epoch++
                }
            }
            if (name in SHOW_OPERATORS) {
                if (!context.inText || textMatrix == null || textLineMatrix == null)
                    rejectPdf("Text outside a valid text object", context.id)
                val valid = when (name) {
                    "Tj", "'" -> args.size==1 && args[0] is COSString
                    "TJ" -> args.size==1 && args[0] is COSArray &&
                        (args[0] as COSArray).all { it is COSString || it is COSNumber }
                    "\"" -> args.size==3 && args[0] is COSNumber && args[1] is COSNumber && args[2] is COSString
                    else -> false
                }
                if (!valid) rejectPdf("Malformed native text operands",context.id)
                val show = PdfShow(context, operation, textMatrix.clone(), textLineMatrix.clone(), graphicsState.clone(), context.epoch, ++paintOrder)
                context.active = show
                context.shows += show
            }
            if (name in PAINT_OPERATORS) {
                if (context.inText) rejectPdf("Painting inside a text object has ambiguous replacement order", context.id)
                context.epoch++
            }
            super.processOperator(operator, args)
        } finally {
            context.active = null
            context.depth--
        }
    }

    override fun showGlyph(matrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
        val show = container.active ?: rejectPdf("Glyph has no stable text operator", container.id)
        if (show.firstMatrix == null) {
            // Quote operators mutate T* and optional Tw/Tc before the glyph callback.
            show.before=textMatrix.clone()
            show.lineAtShow=textLineMatrix.clone()
            show.state=graphicsState.clone()
        }
        val state = graphicsState
        val textState = state.textState
        if (textState.font == null) rejectPdf("Native text has no explicit font resource",show.location)
        if (font is PDType3Font) rejectPdf("Type3 glyph procedures are unsupported", show.location)
        if (font.isVertical) rejectPdf("Vertical text needs an unsupported layout engine", show.location)
        if (font.isDamaged || (!font.isEmbedded && !font.isStandard14))
            rejectPdf("Damaged or non-embedded nonstandard fonts have unsafe substitute metrics", show.location)
        val unicode = font.toUnicode(code)
        if (unicode.isNullOrEmpty() || unicode.contains('\ufffd')) rejectPdf("Native text has no reliable Unicode mapping", show.location)
        if (textState.renderingMode.intValue() >= 4) rejectPdf("Text clipping cannot be translated safely", show.location)
        if (textState.renderingMode.intValue() == 3) {
            invisibleText = true
            show.text.append(unicode)
            return
        }
        if (textState.renderingMode.intValue() != 0) rejectPdf("Stroked text cannot be fitted without changing its ink", show.location)
        if (state.softMask != null || state.blendMode != BlendMode.NORMAL || state.nonStrokeAlphaConstant != 1.0 ||
            state.alphaConstant != 1.0 || state.isAlphaSource || state.isOverprint || state.isNonStrokingOverprint || state.transfer != null)
            rejectPdf("Text transparency, overprint or transfer is not representable safely", show.location)
        if (container.clipped) rejectPdf("Explicitly clipped native text is unsupported", show.location)
        if (textState.fontSize <= 0f || !textState.fontSize.isFinite() || textState.horizontalScaling != 100f ||
            !orthogonalPdfMatrix(textMatrix) || !orthogonalPdfMatrix(state.currentTransformationMatrix))
            rejectPdf("Oblique, compressed or singular text geometry is unsupported", show.location)
        if (state.nonStrokingColor.patternName != null) rejectPdf("Pattern-colored text is unsupported", show.location)
        val localMatrix = Matrix(textState.fontSize,0f,0f,textState.fontSize,0f,textState.rise).multiply(textMatrix)
        val outline = pdfGlyphPath(font, code, show.location)
        outline.transform(pdfAndroidMatrix(font.fontMatrix))
        val local = Path(outline)
        local.transform(pdfAndroidMatrix(localMatrix))
        val localBounds = RectF(); local.computeBounds(localBounds, true)
        outline.transform(pdfAndroidMatrix(matrix))
        val bounds = RectF(); outline.computeBounds(bounds, true)
        if (!bounds.isEmpty) {
            val probe = Region(floor(bounds.left).toInt(),floor(bounds.top).toInt(),ceil(bounds.right).toInt(),ceil(bounds.bottom).toInt())
            probe.op(state.currentClippingPath, Region.Op.DIFFERENCE)
            if (!probe.isEmpty) rejectPdf("Text ink crosses a page or Form clipping boundary", show.location)
            show.ink.union(localBounds)
            show.pageInk.union(bounds)
        }
        show.previousGlyph?.let { previous ->
            val inverse = android.graphics.Matrix()
            if (!pdfAndroidMatrix(previous).invert(inverse)) rejectPdf("Ambiguous glyph advance",show.location)
            val position = floatArrayOf(textMatrix.translateX,textMatrix.translateY)
            inverse.mapPoints(position)
            val gap = position[0]-show.previousAdvance
            val wordGap = maxOf(textState.fontSize*.15f,font.spaceWidth*textState.fontSize/1000f*.45f)
            if (abs(position[1]) <= textState.fontSize*.25f && gap > wordGap &&
                show.text.isNotEmpty() && !show.text.last().isWhitespace() && !unicode.first().isWhitespace())
                show.text.append(' ')
        }
        show.previousGlyph = textMatrix.clone()
        show.previousAdvance = displacement.x*textState.fontSize + textState.characterSpacing
        if (show.firstMatrix == null) show.firstMatrix = textMatrix.clone()
        show.visible = true
        show.text.append(unicode)
    }

    override fun showForm(form: PDFormXObject) {
        val parent = container
        val operationIndex = parent.cursor - 1
        if (!forms.add(form.cosObject)) rejectPdf("Cyclic Form XObject", parent.id)
        if (forms.size > 32) rejectPdf("Form nesting exceeds the safe depth", parent.id)
        if (form.optionalContent != null || form.group != null) rejectPdf("Optional or transparency Form groups are unsupported", parent.id)
        val child = PdfContainer("${parent.id}/Do $operationIndex", parsePdfOperations(PDFStreamParser(form)),
            copyPdfResources(form.resources ?: resources), form = form)
        parent.children[operationIndex] = child
        val parentPath=Path(path); val parentRules=pendingRules.toList(); val parentPoint=point
        path.reset(); pendingRules.clear()
        container = child
        try {
            super.showForm(form)
            checkContainer(child)
        } finally {
            container = parent; forms.remove(form.cosObject)
            path.set(parentPath); pendingRules.clear(); pendingRules.addAll(parentRules); point=parentPoint
        }
    }

    override fun showTransparencyGroup(form: PDTransparencyGroup) = rejectPdf("Transparency groups are unsupported", container.id)
    override fun operatorException(operator: Operator, operands: MutableList<COSBase>, exception: IOException) { throw exception }
    override fun unsupportedOperator(operator: Operator, operands: MutableList<COSBase>) {
        if (operator.name !in setOf("BMC","BDC","EMC","MP","DP","BX","EX"))
            rejectPdf("Unsupported content operator ${operator.name}", container.id)
    }
    fun analyze(): PdfContainer {
        processPage(root.page!!)
        checkContainer(root)
        return root
    }
    private fun checkContainer(value: PdfContainer) {
        if (value.inText || value.markedDepth != 0 || value.clipStack.isNotEmpty() || value.cursor != value.operations.size)
            rejectPdf("Unbalanced or incompletely interpreted content stream", value.id)
        if (value.resources.patternNames.any())
            rejectPdf("Tiling patterns may contain unsupported native text", value.id)
        value.shows.filter { it.visible }.forEach { show ->
            val text = show.text.toString()
            if (PDF_LATIN_PROSE.containsMatchIn(text) &&
                text.any { it in '\u2200'..'\u22ff' || it in '\u0370'..'\u03ff' || it == '=' })
                rejectPdf("Prose and mathematical tokens share an inseparable text operator", show.location)
            text.codePoints().forEach { code ->
                val script=Character.UnicodeScript.of(code)
                if (Character.isLetter(code) && script != Character.UnicodeScript.GREEK && script !in PDF_SIMPLE_SCRIPTS)
                    rejectPdf("Native text requires unsupported shaping or script-specific layout",show.location)
            }
        }
    }
    private fun recordGraphic(outline: Path) {
        val bounds=RectF(); outline.computeBounds(bounds,true)
        if (bounds.isEmpty) return
        val region=Region()
        region.setPath(outline,graphicsState.currentClippingPath)
        root.graphics += PdfGraphic(bounds,region,++paintOrder)
    }
    private fun rectangleRules(a: PointF,b: PointF,c: PointF,d: PointF) {
        pendingRules += PdfRule(a,b); pendingRules += PdfRule(b,c)
        pendingRules += PdfRule(c,d); pendingRules += PdfRule(d,a)
    }
    override fun appendRectangle(a: PointF,b: PointF,c: PointF,d: PointF) {
        path.moveTo(a.x,a.y); path.lineTo(b.x,b.y); path.lineTo(c.x,c.y); path.lineTo(d.x,d.y); path.close()
        rectangleRules(a,b,c,d); point=d
    }
    override fun drawImage(image: PDImage) {
        val outline=Path().apply { addRect(0f,0f,1f,1f,Path.Direction.CW) }
        outline.transform(pdfAndroidMatrix(graphicsState.currentTransformationMatrix))
        recordGraphic(outline)
    }
    override fun clip(windingRule: Path.FillType) { path.fillType=windingRule; graphicsState.intersectClippingPath(path) }
    override fun moveTo(x: Float,y: Float) { path.moveTo(x,y); point=PointF(x,y) }
    override fun lineTo(x: Float,y: Float) {
        val next=PointF(x,y); pendingRules += PdfRule(point,next)
        path.lineTo(x,y); point=next
    }
    override fun curveTo(x1: Float,y1: Float,x2: Float,y2: Float,x3: Float,y3: Float) { path.cubicTo(x1,y1,x2,y2,x3,y3); point=PointF(x3,y3) }
    override fun getCurrentPoint(): PointF = point
    override fun closePath() { path.close() }
    override fun endPath() { path.reset(); pendingRules.clear() }
    private fun recordStroke() {
        val ink=Path()
        Paint().apply {
            style=Paint.Style.STROKE; strokeWidth=transformWidth(graphicsState.lineWidth).coerceAtLeast(.1f)
            strokeCap=graphicsState.lineCap; strokeJoin=graphicsState.lineJoin; strokeMiter=graphicsState.miterLimit
        }.getFillPath(path,ink)
        recordGraphic(ink); root.rules.addAll(pendingRules)
    }
    private fun recordFill(windingRule: Path.FillType) {
        path.fillType=windingRule; recordGraphic(path)
        val bounds=RectF(); path.computeBounds(bounds,true)
        if (bounds.height() <= 2f || bounds.width() <= 2f) root.rules.addAll(pendingRules)
    }
    override fun strokePath() { recordStroke(); endPath() }
    override fun fillPath(windingRule: Path.FillType) { recordFill(windingRule); endPath() }
    override fun fillAndStrokePath(windingRule: Path.FillType) { recordFill(windingRule); recordStroke(); endPath() }
    override fun shadingFill(shadingName: COSName) {
        val region=Region(graphicsState.currentClippingPath)
        val bounds=region.bounds
        root.graphics += PdfGraphic(RectF(bounds),region,++paintOrder)
    }
    companion object {
        val SHOW_OPERATORS = setOf("Tj","TJ","'","\"")
        private val PAINT_OPERATORS = setOf("Do","BI","S","s","f","F","f*","B","B*","b","b*","sh")
    }
}
