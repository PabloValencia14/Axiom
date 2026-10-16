package org.readera.openreadera.data.pdf

import android.content.Context
import android.graphics.Region
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readera.openreadera.translation.NativeTextTranslator
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor

/** Replaces native prose within its original bounds, preserving pages and non-text artwork. */
class PdfDocumentTranslator(context: Context) {
    private val context = context.applicationContext
    init { PDFBoxResourceLoader.init(this.context) }

    suspend fun translateCopy(
        source: File,
        output: File,
        targetLanguage: String,
        translate: NativeTextTranslator,
        onProgress: (Int, Int) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        require(source.canonicalFile != output.canonicalFile) { "The source cannot be overwritten" }
        require(output.name.endsWith(".part") && !output.exists()) { "PDF output must be a new .part file" }
        var ownsOutput = false
        try {
            PDDocument.load(source).use { document ->
                preflightDocument(document)
                val pages = document.pages.toList()
                val roots = pages.mapIndexed { index, page ->
                    currentCoroutineContext().ensureActive()
                    if (page.rotation % 90 != 0 || page.cropBox.width <= 0f || page.cropBox.height <= 0f)
                        rejectPdf("Unsupported crop box or page rotation", "page ${index + 1}")
                    if (page.annotations.any { it.cosObject.containsKey(COSName.AP) })
                        rejectPdf("Annotation appearance text is an unsupported text channel", "page ${index + 1}")
                    val analyzer = PdfTranslationAnalyzer(page, index)
                    val root = analyzer.analyze()
                    if (analyzer.invisibleText) rejectPdf("Invisible OCR text is not native visible prose", root.id)
                    root
                }
                val sourceParagraphs = roots.flatMap(::groupPdfParagraphs)
                val paragraphs = sourceParagraphs.filter { it.hasProse }
                if (paragraphs.isEmpty())
                    rejectPdf("No visible native prose; image-only, blank or decorative PDFs are not translatable", "document")
                val fonts = PdfTranslationFonts(context, document)
                paragraphs.forEach { paragraph ->
                    val prose = paragraph.parts.filterIsInstance<PdfParagraphPart.Text>()
                    prose.forEach { part ->
                        part.glyphs.forEach { ref ->
                            val ink = ref.glyph.pageInk
                            if (!ink.isEmpty) {
                                val outside = Region(floor(ink.left).toInt(), floor(ink.top).toInt(),
                                    ceil(ink.right).toInt(), ceil(ink.bottom).toInt())
                                outside.op(ref.show.state.currentClippingPath, Region.Op.DIFFERENCE)
                                if (!outside.isEmpty) rejectPdf("Native prose is clipped by a page or Form boundary", ref.show.location)
                            }
                        }
                        requireSimplePdfScript(part.text, paragraph.first.location)
                        val sourceFont = part.sourceStyle.state.textState.font
                            ?: rejectPdf("Text style has no source font", part.sourceStyle.location)
                        fonts.choose(sourceFont, part.text, part.sourceStyle.location)
                    }
                }
                val translated = mutableListOf<PdfInPlaceComposer.Translation>()
                onProgress(0, paragraphs.size)
                paragraphs.forEachIndexed { index, paragraph ->
                    currentCoroutineContext().ensureActive()
                    var changed = false
                    val parts = paragraph.translationParts().map { part ->
                        currentCoroutineContext().ensureActive()
                        when (part) {
                            is PdfTranslatedPart.Protected -> part
                            is PdfTranslatedPart.Text -> {
                                val leading = part.text.takeWhile(Char::isWhitespace)
                                val trailing = part.text.takeLastWhile(Char::isWhitespace)
                                val body = part.text.drop(leading.length).dropLast(trailing.length)
                                if (body.isEmpty()) part
                                else {
                                    val response = translate(body, targetLanguage).getOrThrow()
                                    if (response.isBlank())
                                        throw IOException("Translation provider returned blank PDF prose")
                                    changed = changed || response != body
                                    part.copy(text = leading + response + trailing)
                                }
                            }
                        }
                    }
                    if (changed) translated += PdfInPlaceComposer.Translation(paragraph, parts)
                    onProgress(index + 1, paragraphs.size)
                }
                PdfInPlaceComposer(document, fonts).compose(roots, translated)
                if (!output.createNewFile()) throw IOException("PDF output already exists")
                ownsOutput = true
                document.save(output)
                currentCoroutineContext().ensureActive()
            }
        } catch (failure: Throwable) {
            if (ownsOutput && output.exists() && !output.delete())
                failure.addSuppressed(IOException("Could not remove failed PDF part file"))
            throw failure
        }
    }

    private fun preflightDocument(document: PDDocument) {
        if (!document.currentAccessPermission.canModify() || !document.currentAccessPermission.canExtractContent())
            rejectPdf("PDF permissions prohibit text extraction or modification", "document")
        if (document.signatureDictionaries.isNotEmpty() || document.documentCatalog.cosObject.containsKey(COSName.PERMS))
            rejectPdf("Signed or certified PDFs cannot be translated without invalidating the signature", "document")
        val catalog = document.documentCatalog
        if (catalog.acroForm?.fields?.isNotEmpty() == true)
            rejectPdf("PDF forms contain unsupported editable text channels", "document")
        requireSafePdfMetadata(catalog.cosObject.getDictionaryObject(COSName.STRUCT_TREE_ROOT), "document structure")
    }
}
