package org.readera.openreadera.data.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.Writer

data class PdfOcrLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)
data class PdfOcrPage(val width: Int, val height: Int, val lines: List<PdfOcrLine>)
data class PdfDocumentSearchResult(val page: Int, val snippet: String)
data class PdfSignaturePoint(val x: Float, val y: Float)
data class PdfSignatureStroke(val points: List<PdfSignaturePoint>)
enum class PdfSignatureAlignment { LEFT, CENTER, RIGHT }
data class PdfVisualSignature(
    val pageIndex: Int,
    val strokes: List<PdfSignatureStroke>,
    val alignment: PdfSignatureAlignment,
    val canvasAspectRatio: Float
)

class PdfDocumentEditor(context: Context) {
    private val appContext = context.applicationContext

    init {
        if (!initialized) synchronized(initializationLock) {
            if (!initialized) {
                PDFBoxResourceLoader.init(appContext)
                initialized = true
            }
        }
    }


    suspend fun addVisualSignature(input: File, signature: PdfVisualSignature, output: File): File =
        transform(input, output) { source, target ->
            requirePageIndex(signature.pageIndex, source.numberOfPages)
            require(signature.canvasAspectRatio.isFinite() && signature.canvasAspectRatio in MIN_CANVAS_ASPECT..MAX_CANVAS_ASPECT) {
                "Signature canvas aspect ratio is invalid"
            }
            require(signature.strokes.isNotEmpty() && signature.strokes.size <= MAX_SIGNATURE_STROKES) {
                "Signature must contain a bounded number of strokes"
            }
            var pointCount = 0
            signature.strokes.forEach { stroke ->
                require(stroke.points.size >= 2) { "Signature strokes must contain at least two points" }
                pointCount += stroke.points.size
                require(pointCount <= MAX_SIGNATURE_POINTS) { "Signature contains too many points" }
                require(stroke.points.all { point ->
                    point.x.isFinite() && point.y.isFinite() &&
                        point.x in 0f..1f && point.y in 0f..1f
                }) { "Signature points must be finite normalized coordinates" }
            }

            source.pages.forEachIndexed { index, page ->
                val imported = target.importPage(page)
                if (index == signature.pageIndex) {
                    val box = imported.cropBox
                    val rotation = ((imported.rotation % 360) + 360) % 360
                    val mediaBox = imported.mediaBox
                    require((rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) &&
                        box.lowerLeftX.isFinite() && box.lowerLeftY.isFinite() &&
                        box.upperRightX.isFinite() && box.upperRightY.isFinite() &&
                        mediaBox.lowerLeftX.isFinite() && mediaBox.lowerLeftY.isFinite() &&
                        mediaBox.upperRightX.isFinite() && mediaBox.upperRightY.isFinite() &&
                        box.width.isFinite() && box.height.isFinite() &&
                        mediaBox.width.isFinite() && mediaBox.height.isFinite() &&
                        box.width > 0f && box.height > 0f && mediaBox.width > 0f && mediaBox.height > 0f &&
                        box.lowerLeftX >= mediaBox.lowerLeftX && box.lowerLeftY >= mediaBox.lowerLeftY &&
                        box.upperRightX <= mediaBox.upperRightX && box.upperRightY <= mediaBox.upperRightY) {
                        "Unsupported page geometry for signature placement"
                    }
                    val quarterTurn = rotation == 90 || rotation == 270
                    val displayWidth = if (quarterTurn) box.height else box.width
                    val displayHeight = if (quarterTurn) box.width else box.height
                    val sideMargin = displayWidth * SIGNATURE_SIDE_MARGIN_FRACTION
                    val maxWidth = displayWidth - 2f * sideMargin
                    val maxHeight = displayHeight * SIGNATURE_HEIGHT_FRACTION
                    val signatureWidth = minOf(maxWidth, maxHeight * signature.canvasAspectRatio)
                    val signatureHeight = signatureWidth / signature.canvasAspectRatio
                    val startX = when (signature.alignment) {
                        PdfSignatureAlignment.LEFT -> sideMargin
                        PdfSignatureAlignment.CENTER -> (displayWidth - signatureWidth) / 2f
                        PdfSignatureAlignment.RIGHT -> displayWidth - sideMargin - signatureWidth
                    }
                    val startY = displayHeight * SIGNATURE_TOP_FRACTION
                    PDPageContentStream(target, imported, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                        stream.setLineWidth(1.5f)
                        signature.strokes.forEach { stroke ->
                            stroke.points.forEachIndexed { pointIndex, point ->
                                emitSignaturePoint(
                                    stream, box, rotation, point, startX, startY, signatureWidth, signatureHeight,
                                    move = pointIndex == 0
                                )
                            }
                            stream.stroke()
                        }
                    }
                }
            }
        }

    private fun emitSignaturePoint(
        stream: PDPageContentStream,
        box: com.tom_roush.pdfbox.pdmodel.common.PDRectangle,
        rotation: Int,
        point: PdfSignaturePoint,
        startX: Float,
        startY: Float,
        width: Float,
        height: Float,
        move: Boolean
    ) {
        val x = startX + point.x * width
        val y = startY + point.y * height
        val pdfX: Float
        val pdfY: Float
        when (rotation) {
            0 -> { pdfX = box.lowerLeftX + x; pdfY = box.upperRightY - y }
            90 -> { pdfX = box.lowerLeftX + y; pdfY = box.lowerLeftY + x }
            180 -> { pdfX = box.upperRightX - x; pdfY = box.lowerLeftY + y }
            270 -> { pdfX = box.upperRightX - y; pdfY = box.upperRightY - x }
            else -> error("Unsupported page rotation")
        }
        if (move) stream.moveTo(pdfX, pdfY) else stream.lineTo(pdfX, pdfY)
    }

    fun searchBlocking(input: File, query: String, maxResults: Int = 50): List<PdfDocumentSearchResult> {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "PDF text search must run off the main thread"
        }
        val normalizedQuery = normalizeWhitespace(query)
        if (normalizedQuery.isEmpty() || maxResults <= 0) return emptyList()
        return try {
            load(input).use { document ->
                val stripper = PDFTextStripper()
                val results = ArrayList<PdfDocumentSearchResult>(maxResults)
                for (pageIndex in 0 until document.numberOfPages) {
                    stripper.startPage = pageIndex + 1
                    stripper.endPage = pageIndex + 1
                    val text = normalizeWhitespace(stripper.getText(document))
                    var match = text.indexOf(normalizedQuery, ignoreCase = true)
                    while (match >= 0 && results.size < maxResults) {
                        val start = (match - 35).coerceAtLeast(0)
                        val end = (match + normalizedQuery.length + 35).coerceAtMost(text.length)
                        results += PdfDocumentSearchResult(
                            pageIndex,
                            buildString {
                                if (start > 0) append('…')
                                append(text, start, end)
                                if (end < text.length) append('…')
                            }
                        )
                        match = text.indexOf(normalizedQuery, match + normalizedQuery.length, ignoreCase = true)
                    }
                    if (results.size >= maxResults) break
                }
                results
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeWhitespace(value: String): String = buildString(value.length) {
        var pendingSpace = false
        for (character in value) {
            if (character.isWhitespace()) {
                if (isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) append(' ')
                append(character)
                pendingSpace = false
            }
        }
    }

    suspend fun pageCount(input: File): Int = withContext(Dispatchers.IO) {
        load(input).use { it.numberOfPages }
    }

    suspend fun extractPageText(input: File, pageIndex: Int): String = withContext(Dispatchers.IO) {
        load(input).use { document ->
            requirePageIndex(pageIndex, document.numberOfPages)
            PDFTextStripper().apply { startPage = pageIndex + 1; endPage = pageIndex + 1 }.getText(document)
        }
    }

    suspend fun extractDocumentText(input: File, maxChars: Int): String = withContext(Dispatchers.IO) {
        require(maxChars >= 0) { "maxChars must not be negative" }
        load(input).use { document ->
            val result = StringBuilder(minOf(maxChars, 8_192))
            for (pageIndex in 0 until document.numberOfPages) {
                if (result.length >= maxChars) break
                val pageText = extractPageTextBounded(document, pageIndex, maxChars - result.length)
                if (pageText.isBlank()) continue
                if (result.isNotEmpty()) {
                    result.append("\n\n".take(maxChars - result.length))
                }
                result.append(pageText.take(maxChars - result.length))
            }
            result.toString()
        }
    }

    private fun extractPageTextBounded(document: PDDocument, pageIndex: Int, maxChars: Int): String {
        val result = StringBuilder(minOf(maxChars, 8_192))
        val boundedWriter = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, length: Int) {
                val remaining = maxChars - result.length
                if (remaining > 0) result.append(buffer, offset, minOf(length, remaining))
            }

            override fun flush() = Unit
            override fun close() = Unit
        }
        PDFTextStripper().apply {
            startPage = pageIndex + 1
            endPage = pageIndex + 1
        }.writeText(document, boundedWriter)
        return result.toString()
    }

    fun writeAllPageTextBlocking(input: File, writer: Writer) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "PDF text export must run off the main thread"
        }
        load(input).use { document ->
            val stripper = PDFTextStripper()
            for (pageIndex in 0 until document.numberOfPages) {
                if (pageIndex > 0) writer.write("\n\n")
                stripper.startPage = pageIndex + 1
                stripper.endPage = pageIndex + 1
                stripper.writeText(document, writer)
            }
        }
    }



    suspend fun extractPages(input: File, pageIndices: List<Int>, output: File): File = transform(input, output) { source, target ->
        require(pageIndices.isNotEmpty()) { "At least one page must be extracted" }
        require(pageIndices.all { it in 0 until source.numberOfPages }) { "Page index out of range" }
        pageIndices.forEach { target.importPage(source.getPage(it)) }
    }

    suspend fun applyPagePlan(
        input: File,
        order: List<Int>,
        deletedPageIndices: Set<Int>,
        rotations: Map<Int, Int>,
        output: File
    ): File = transform(input, output) { source, target ->
        val count = source.numberOfPages
        require(order.size == count && order.toSet().size == count && order.all { it in 0 until count }) {
            "Order must be a permutation of every page"
        }
        require(deletedPageIndices.all { it in 0 until count }) { "Deleted page index out of range" }
        require(deletedPageIndices.size < count) { "Cannot delete every page" }
        require(rotations.keys.all { it in 0 until count && it !in deletedPageIndices }) {
            "Rotations must target retained pages"
        }
        require(rotations.values.all { it % 90 == 0 }) { "Rotation must be a multiple of 90 degrees" }
        order.filterNot { it in deletedPageIndices }.forEach { index ->
            val page = source.getPage(index)
            val imported = target.importPage(page)
            rotations[index]?.let { degrees ->
                imported.rotation = ((page.rotation + degrees) % 360 + 360) % 360
            }
        }
    }

    suspend fun merge(inputs: List<File>, output: File): File = withContext(Dispatchers.IO) {
        require(inputs.isNotEmpty()) { "At least one input is required" }
        val destination = output.canonicalFile
        require(inputs.none { it.canonicalFile == destination }) { "Output must not be an input" }
        atomicWrite(output) { temp ->
            val merger = PDFMergerUtility()
            inputs.forEach { merger.addSource(it) }
            merger.destinationFileName = temp.absolutePath
            merger.mergeDocuments(null)
            load(temp).use { require(it.numberOfPages > 0) { "Merged PDF has no pages" } }
        }
    }

    suspend fun addOcrTextLayer(input: File, pages: List<PdfOcrPage>, output: File): File = transform(input, output) { source, target ->
        require(pages.size == source.numberOfPages) { "OCR page count must match PDF" }
        source.pages.forEachIndexed { index, page ->
            val targetPage = target.importPage(page)
            val ocr = pages[index]
            require(ocr.width > 0 && ocr.height > 0) { "OCR image dimensions must be positive" }
            val box = targetPage.cropBox
            val scaleX = box.width / ocr.width
            val scaleY = box.height / ocr.height
            PDPageContentStream(target, targetPage, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 1f)
                stream.setRenderingMode(RenderingMode.NEITHER)
                for (line in ocr.lines) {
                    if (line.text.isBlank()) continue
                    require(listOf(line.left, line.top, line.right, line.bottom).all(Float::isFinite) &&
                        line.left <= line.right && line.top <= line.bottom) { "Invalid OCR bounds" }
                    val x = (line.left * scaleX).coerceIn(0f, box.width)
                    val y = (box.height - line.bottom * scaleY).coerceIn(0f, box.height)
                    stream.newLineAtOffset(x, y)
                    stream.showText(line.text.filter { it.code in 32..255 })
                    stream.newLineAtOffset(-x, -y)
                }
                stream.endText()
            }
        }
    }

    private suspend fun transform(input: File, output: File, operation: (PDDocument, PDDocument) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(input.canonicalFile != output.canonicalFile) { "Output must not be the input" }
            atomicWrite(output) { temp ->
                load(input).use { source -> PDDocument().use { target ->
                    operation(source, target)
                    require(target.numberOfPages > 0) { "Output PDF has no pages" }
                    target.save(temp)
                } }
            }
        }

    private fun load(file: File): PDDocument {
        require(file.isFile && file.canRead() && file.length() > 0) { "Input PDF is not readable" }
        return try { PDDocument.load(file) } catch (e: Exception) { throw IOException("Unable to read PDF", e) }
    }

    private fun atomicWrite(output: File, write: (File) -> Unit): File {
        val parent = output.absoluteFile.parentFile ?: throw IOException("Output has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Cannot create output directory")
        if (!parent.isDirectory || !parent.canWrite()) throw IOException("Output directory is not writable")
        val temp = File(parent, ".${output.name}.${System.nanoTime()}.tmp")
        try {
            write(temp)
            if (!temp.isFile || temp.length() == 0L) throw IOException("PDF output is empty")
            val backup = if (output.exists()) File(parent, ".${output.name}.${System.nanoTime()}.bak") else null
            if (backup != null && !output.renameTo(backup)) throw IOException("Cannot preserve existing output")
            try {
                if (!temp.renameTo(output)) throw IOException("Cannot finalize PDF output")
                backup?.delete()
                return output
            } catch (failure: Exception) {
                if (backup != null && backup.exists() && !backup.renameTo(output)) {
                    throw IOException("Cannot finalize output or restore previous output", failure)
                }
                throw failure
            }
        } finally { if (temp.exists()) temp.delete() }
    }

    private fun requirePageIndex(index: Int, count: Int) = require(index in 0 until count) { "Page index out of range" }

    companion object {
        private val initializationLock = Any()
        private const val MAX_SIGNATURE_STROKES = 100
        private const val MAX_SIGNATURE_POINTS = 10_000
        private const val SIGNATURE_WIDTH_FRACTION = 0.35f
        private const val SIGNATURE_SIDE_MARGIN_FRACTION = 0.04f
        private const val SIGNATURE_TOP_FRACTION = 0.82f
        private const val SIGNATURE_HEIGHT_FRACTION = 0.12f
        private const val MIN_CANVAS_ASPECT = 1.2f
        private const val MAX_CANVAS_ASPECT = 6f
        @Volatile private var initialized = false
    }
}
    
