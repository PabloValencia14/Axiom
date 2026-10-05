package org.readera.openreadera.data.pdf

import android.content.Context
import android.graphics.RectF
import android.graphics.Region
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readera.openreadera.translation.NativeTextTranslator
import org.readera.openreadera.translation.TranslationFitPolicy
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Rewrites selectable, visible native text; never reconstructs scans or publishes the copy. */
class PdfDocumentTranslator(context: Context) {
    private val context = context.applicationContext
    init { PDFBoxResourceLoader.init(this.context) }

    suspend fun translateCopy(
        source: File,
        output: File,
        targetLanguage: String,
        translate: NativeTextTranslator,
        onProgress: (Int, Int) -> Unit,
        fit: TranslationFitPolicy = TranslationFitPolicy()
    ): Unit = withContext(Dispatchers.IO) {
        require(source.canonicalFile != output.canonicalFile) { "The source cannot be overwritten" }
        require(output.name.endsWith(".part") && !output.exists()) { "PDF output must be a new .part file" }
        require(fit.minFontScale.isFinite() && fit.minFontScale in .85f..1f &&
            fit.minLeadingScale.isFinite() && fit.minLeadingScale in .90f..1f &&
            fit.minimumFontPt.isFinite() && fit.minimumFontPt >= 9f) { "Unsafe PDF fitting policy" }
        var ownsOutput = false
        try {
            PDDocument.load(source).use { document ->
                preflightDocument(document)
                val roots = document.pages.mapIndexed { index,page ->
                    currentCoroutineContext().ensureActive()
                    if (page.rotation % 90 != 0 || page.cropBox.width <= 0f || page.cropBox.height <= 0f)
                        rejectPdf("Unsupported crop box or page rotation", "page ${index+1}")
                    if (page.annotations.any { it.cosObject.containsKey(COSName.AP) })
                        rejectPdf("Annotation appearance text is an unsupported text channel", "page ${index+1}")
                    val analyzer = PdfTranslationAnalyzer(page,index)
                    val root = analyzer.analyze()
                    // A page containing an invisible OCR channel cannot be reported as translated prose.
                    if (analyzer.invisibleText) rejectPdf("Invisible OCR text is not native visible prose", root.id)
                    root
                }
                val paragraphs = roots.flatMap { groupPdfParagraphs(it) }
                if (paragraphs.isEmpty()) rejectPdf("No visible native prose; image-only, blank or decorative PDFs are not translatable", "document")
                paragraphs.forEach { paragraph ->
                    requireSimplePdfScript(paragraph.text,paragraph.first.location)
                    paragraph.shows.forEach { show ->
                        restoreAdvance(show) // Ensure the insertion can restore both source text matrices before any provider call.
                    }
                }
                val fonts = PdfTranslationFonts(context,document)
                val fitted = mutableListOf<PdfFittedParagraph>()
                onProgress(0,paragraphs.size)
                paragraphs.forEachIndexed { index,paragraph ->
                    currentCoroutineContext().ensureActive()
                    val translated = translate(paragraph.text,targetLanguage).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    val font = fonts.choose(paragraph.first.state.textState.font,translated,paragraph.first.location)
                    fitted += fitPdfParagraph(paragraph,translated,font,fit)
                    onProgress(index+1,paragraphs.size)
                }
                checkCollisions(roots,fitted)
                roots.forEach { root ->
                    currentCoroutineContext().ensureActive()
                    rewrite(document,root,fitted)
                }
                // ContentStreamWriter does not register fonts with PDPageContentStream's automatic subset set.
                fitted.map { it.font }.distinct().filter { it.willBeSubset() }.forEach { it.subset() }
                currentCoroutineContext().ensureActive()
                if (!output.createNewFile()) throw IOException("PDF output already exists")
                ownsOutput = true
                document.save(output)
                currentCoroutineContext().ensureActive()
            }
        } catch (failure: Throwable) {
            if (ownsOutput && output.exists() && !output.delete()) failure.addSuppressed(IOException("Could not remove failed PDF part file"))
            throw failure
        }
    }

    private fun preflightDocument(document: PDDocument) {
        if (!document.currentAccessPermission.canModify() || !document.currentAccessPermission.canExtractContent())
            rejectPdf("PDF permissions prohibit text extraction or modification", "document")
        if (document.signatureDictionaries.isNotEmpty() || document.documentCatalog.cosObject.containsKey(COSName.PERMS))
            rejectPdf("Signed or certified PDFs cannot be translated without invalidating the signature", "document")
        val catalog = document.documentCatalog
        if (catalog.acroForm?.fields?.isNotEmpty() == true || catalog.cosObject.containsKey(COSName.OCPROPERTIES))
            rejectPdf("Forms or optional content need unsupported text channels", "document")
        requireSafePdfMetadata(catalog.cosObject.getDictionaryObject(COSName.STRUCT_TREE_ROOT),"document structure")
    }

    private fun checkCollisions(roots: List<PdfContainer>, fitted: List<PdfFittedParagraph>) {
        val newInk = fitted.associateWith { replacement ->
            val transform = pdfAndroidMatrix(replacement.paragraph.basis.multiply(replacement.paragraph.first.state.currentTransformationMatrix))
            replacement.ink.filterNot { it.isEmpty }.map { RectF(it).also(transform::mapRect) }
        }
        roots.forEach { root ->
            val sourceShows = allPdfShows(root)
            val replacements = fitted.filter { it.paragraph.first.container.id.substringBefore('/') == root.id }
            replacements.forEach { replacement ->
                val show = replacement.paragraph.first
                val oldArea=RectF()
                val oldInk=Region()
                replacement.paragraph.shows.forEach { original ->
                    oldArea.union(original.pageInk)
                    val bounds=original.pageInk
                    oldInk.op(Region(floor(bounds.left).toInt(),floor(bounds.top).toInt(),ceil(bounds.right).toInt(),ceil(bounds.bottom).toInt()),Region.Op.UNION)
                }
                newInk.getValue(replacement).forEach { ink ->
                    val region = Region(floor(ink.left).toInt(),floor(ink.top).toInt(),ceil(ink.right).toInt(),ceil(ink.bottom).toInt())
                    region.op(show.state.currentClippingPath,Region.Op.DIFFERENCE)
                    if (!region.isEmpty) rejectPdf("Translated ink crosses a crop or Form boundary",show.location)
                    root.graphics.forEach { graphic ->
                        if (RectF.intersects(ink,graphic.bounds)) {
                            val originalEnvelope=Region(floor(oldArea.left).toInt(),floor(oldArea.top).toInt(),
                                ceil(oldArea.right).toInt(),ceil(oldArea.bottom).toInt())
                            originalEnvelope.op(graphic.region,Region.Op.DIFFERENCE)
                            val underlyingBackground=graphic.paintOrder<show.paintOrder && originalEnvelope.isEmpty
                            if (!underlyingBackground) {
                                val introduced=Region(floor(ink.left).toInt(),floor(ink.top).toInt(),ceil(ink.right).toInt(),ceil(ink.bottom).toInt())
                                introduced.op(graphic.region,Region.Op.INTERSECT)
                                introduced.op(oldInk,Region.Op.DIFFERENCE)
                                if (!introduced.isEmpty)
                                    rejectPdf("Translated ink invades adjacent image or graphic geometry",show.location)
                            }
                        }
                    }
                    sourceShows.filter { it !in replacement.paragraph.shows && it.visible }.forEach { neighbor ->
                        if (RectF.intersects(ink,neighbor.pageInk))
                            rejectPdf("Translated ink collides with neighboring native text",show.location)
                    }
                }
            }
            replacements.forEachIndexed { index,a -> replacements.drop(index+1).forEach { b ->
                if (newInk.getValue(a).any { x -> newInk.getValue(b).any { y -> RectF.intersects(x,y) } })
                    rejectPdf("Translated paragraphs collide",a.paragraph.first.location)
            } }
        }
    }

    private fun rewrite(document: PDDocument, container: PdfContainer, replacements: List<PdfFittedParagraph>): PDFormXObject? {
        if (replacements.none { it.paragraph.first.container === container ||
                it.paragraph.first.container.id.startsWith("${container.id}/") }) return container.form
        val children = container.children.mapValues { (_,child) -> rewrite(document,child,replacements)!! }
        val local = replacements.filter { it.paragraph.first.container === container }
        val selected = local.flatMap { it.paragraph.shows }.associateBy { it.operation.index }
        val insertion = local.associateBy { it.paragraph.first.operation.index }
        val tokens = mutableListOf<Any>()
        container.operations.forEach { operation ->
            insertion[operation.index]?.let { replacement ->
                tokens.addAll(insertedParagraph(container,replacement))
            }
            val show = selected[operation.index]
            val child = children[operation.index]
            when {
                show != null -> tokens.addAll(suppressedShow(show))
                child != null -> {
                    tokens += container.resources.add(child)
                    tokens += Operator.getOperator("Do")
                }
                else -> { tokens.addAll(operation.operands); tokens += operation.operator }
            }
        }
        val stream = PDStream(document)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
        if (container.page != null) {
            container.page.resources = container.resources
            container.page.setContents(stream)
            return null
        }
        val original = container.form!!
        // Copy every Form attribute except storage/Resources; no shared Form is globally mutated.
        original.cosObject.keySet().forEach { key ->
            if (key !in setOf(COSName.LENGTH,COSName.FILTER,COSName.DECODE_PARMS,COSName.RESOURCES))
                stream.cosObject.setItem(key,original.cosObject.getItem(key))
        }
        return PDFormXObject(stream).also { it.resources=container.resources }
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
            val matrix = paragraph.basis.clone().apply { concatenate(Matrix.getTranslateInstance(0f,-index*fitted.leading)) }
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

    private fun suppressedShow(show: PdfShow): List<Any> = buildList {
        val original = show.operation
        val state = show.state.textState
        var word = state.wordSpacing; var character = state.characterSpacing
        fun op(name: String,vararg values: Any) { addAll(values); add(Operator.getOperator(name)) }
        if (original.operator.name == "\"") {
            word=(original.operands[0] as COSNumber).floatValue(); character=(original.operands[1] as COSNumber).floatValue()
            op("Tw",original.operands[0]); op("Tc",original.operands[1])
        }
        if (original.operator.name == "'" || original.operator.name == "\"") op("T*")
        val array = COSArray()
        fun advance(string: COSString) {
            val input=ByteArrayInputStream(string.bytes)
            while (input.available()>0) {
                val before=input.available(); val code=state.font.readCode(input)
                var delta=state.font.getDisplacement(code).x*1000f + character*1000f/state.fontSize
                if (code==32 && before-input.available()==1) delta += word*1000f/state.fontSize
                // Keep glyph-wise advances, rather than collapsing a string into one rounded displacement.
                array.add(COSFloat(-delta))
            }
        }
        if (original.operator.name == "TJ") {
            (original.operands.single() as COSArray).forEach { value ->
                when (value) {
                    is COSString -> advance(value)
                    is COSNumber -> array.add(value)
                    else -> rejectPdf("Invalid TJ array",show.location)
                }
            }
        } else advance(original.operands.last() as COSString)
        op("TJ",array)
    }

    private fun matrixValues(matrix: Matrix): Array<Any> = arrayOf(COSFloat(matrix.scaleX),COSFloat(matrix.shearY),
        COSFloat(matrix.shearX),COSFloat(matrix.scaleY),COSFloat(matrix.translateX),COSFloat(matrix.translateY))
}
