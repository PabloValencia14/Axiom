package org.readera.openreadera.data.pdf

import android.content.Context
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

/** Copies original PDF artwork as vector bands and adds selectable translated prose below it. */
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
                        requireSimplePdfScript(part.text, paragraph.first.location)
                        val sourceFont = part.sourceStyle.state.textState.font
                            ?: rejectPdf("Text style has no source font", part.sourceStyle.location)
                        fonts.choose(sourceFont, part.text, part.sourceStyle.location)
                    }
                }
                val translated = mutableListOf<PdfBilingualComposer.Translation>()
                onProgress(0, paragraphs.size)
                paragraphs.forEachIndexed { index, paragraph ->
                    currentCoroutineContext().ensureActive()
                    val request = paragraph.translationRequest()
                    val response = translate(request.text, targetLanguage).getOrThrow()
                    if (response != request.text)
                        translated += PdfBilingualComposer.Translation(paragraph, request.parse(response))
                    onProgress(index + 1, paragraphs.size)
                }
                PdfBilingualComposer(document, fonts).compose(pages, roots, sourceParagraphs, translated)
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
