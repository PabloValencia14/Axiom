package org.readera.openreadera.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.readera.openreadera.data.pdf.PdfDocumentEditor
import org.readera.openreadera.data.pdf.PdfOcrLine
import org.readera.openreadera.data.pdf.PdfOcrPage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter

class DocumentScanProcessor(context: Context) {
    private val appContext = context.applicationContext
    private val editor = PdfDocumentEditor(appContext)
    private val scansDir = File(appContext.filesDir, "scans")

    suspend fun importScannedPdf(uri: Uri, title: String): File = withContext(Dispatchers.IO) {
        val name = safeBaseName(title, "scan") + ".pdf"
        val destination = uniqueFile(scansDir, name)
        copyUriAtomically(uri, destination)
        try {
            require(editor.pageCount(destination) > 0) { "Selected file is not a valid PDF" }
            destination
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    suspend fun makeSearchableCopy(
        source: File,
        destination: File,
        onProgress: (Int, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        require(source.isFile && source.canRead()) { "Source PDF is not readable" }
        require(source.length() <= MAX_OCR_INPUT_BYTES) { "PDF exceeds the OCR file-size limit" }
        require(!sameFile(source, destination)) { "Source and destination must differ" }
        require(destination.parentFile?.let { it.isDirectory || it.mkdirs() } != false) {
            "Cannot create destination directory"
        }
        val count = editor.pageCount(source)
        require(count in 1..MAX_OCR_PAGES) { "PDF exceeds the OCR page limit" }
        val ocrPages = ArrayList<PdfOcrPage>(count)
        var recognizedChars = 0
        var recognizedLines = 0
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val renderer = android.graphics.pdf.PdfRenderer(descriptor)
                try {
                    require(renderer.pageCount == count) { "PDF page count changed during OCR" }
                    for (index in 0 until renderer.pageCount) {
                        val page = renderer.openPage(index)
                        val bitmap = try {
                            val scale = minOf(2f, 2400f / maxOf(page.width, page.height))
                            val width = (page.width * scale).toInt().coerceAtLeast(1)
                            val height = (page.height * scale).toInt().coerceAtLeast(1)
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
                                page.render(image, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        } finally {
                            page.close()
                        }
                        try {
                            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                            val lines = ArrayList<PdfOcrLine>()
                            for (block in result.textBlocks) {
                                for (line in block.lines) {
                                    val box = line.boundingBox ?: continue
                                    if (recognizedLines >= MAX_OCR_LINES) {
                                        throw IOException("PDF exceeds the OCR line limit")
                                    }
                                    recognizedLines++
                                    if (line.text.length > MAX_OCR_TEXT_CHARS - recognizedChars) {
                                        throw IOException("PDF exceeds the OCR text limit")
                                    }
                                    recognizedChars += line.text.length
                                    lines += PdfOcrLine(
                                        text = line.text,
                                        left = box.left.toFloat(), top = box.top.toFloat(),
                                        right = box.right.toFloat(), bottom = box.bottom.toFloat()
                                    )
                                }
                            }
                            ocrPages += PdfOcrPage(bitmap.width, bitmap.height, lines)
                        } finally {
                            bitmap.recycle()
                        }
                        onProgress(index + 1, count)
                    }
                } finally {
                    renderer.close()
                }
            }
        } finally {
            recognizer.close()
        }
        editor.addOcrTextLayer(source, ocrPages, destination)
    }

    suspend fun imageUrisToPdf(uris: List<Uri>, title: String): File = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "At least one image is required" }
        if (!scansDir.isDirectory && !scansDir.mkdirs()) throw IOException("Cannot create scans directory")
        val destination = uniqueFile(scansDir, safeBaseName(title, "scan") + ".pdf")
        val pdf = PdfDocument()
        try {
            uris.forEachIndexed { index, uri ->
                val bitmap = decodeImage(uri)
                try {
                    val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                    val page = pdf.startPage(pageInfo)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    pdf.finishPage(page)
                } finally {
                    bitmap.recycle()
                }
            }
            writePdfAtomically(pdf, destination)
        } finally {
            pdf.close()
        }
    }

    suspend fun pdfToImages(source: File, outputDir: File): List<File> = withContext(Dispatchers.IO) {
        require(source.isFile && source.canRead()) { "Source PDF is not readable" }
        require(outputDir.isDirectory || outputDir.mkdirs()) { "Cannot create output directory" }
        val output = ArrayList<File>()
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val renderer = android.graphics.pdf.PdfRenderer(descriptor)
            try {
                for (index in 0 until renderer.pageCount) {
                    val page = renderer.openPage(index)
                    val bitmap = try {
                        val scale = minOf(2f, 2400f / maxOf(page.width, page.height))
                        Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceAtLeast(1),
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        ).also { page.render(it, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    } finally {
                        page.close()
                    }
                    try {
                        val file = uniqueFile(outputDir, "page-${index + 1}.png")
                        atomicWrite(file) { stream ->
                            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException("Image encoding failed")
                        }
                        output += file
                    } finally {
                        bitmap.recycle()
                    }
                }
            } finally {
                renderer.close()
            }
        }
        output
    }

    suspend fun pdfToText(source: File): File = withContext(Dispatchers.IO) {
        require(source.isFile && source.canRead()) { "Source PDF is not readable" }
        if (!scansDir.isDirectory && !scansDir.mkdirs()) throw IOException("Cannot create scans directory")
        val destination = uniqueFile(scansDir, safeBaseName(source.nameWithoutExtension, "document") + ".txt")
        atomicWrite(destination) { stream ->
            val writer = OutputStreamWriter(stream, Charsets.UTF_8)
            editor.writeAllPageTextBlocking(source, writer)
            writer.flush()
        }
        destination
    }

    private fun decodeImage(uri: Uri): Bitmap {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri) ?: throw IOException("Cannot read image")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Selected file is not a valid image")

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_IMAGE_DIMENSION) {
            sampleSize *= 2
        }
        val bitmap = BitmapFactory.Options().let { options ->
            options.inSampleSize = sampleSize
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } ?: throw IOException("Cannot decode image")

        val degrees = resolver.openInputStream(uri)?.use { stream ->
            runCatching { ExifInterface(stream).rotationDegrees }.getOrDefault(0)
        } ?: 0
        if (degrees == 0) return bitmap
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true
        )
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun copyUriAtomically(uri: Uri, destination: File): File {
        if (!scansDir.isDirectory && !scansDir.mkdirs()) throw IOException("Cannot create scans directory")
        val type = appContext.contentResolver.getType(uri)
        require(type == "application/pdf" || uri.lastPathSegment.orEmpty().endsWith(".pdf", true)) { "Selected file is not a PDF" }
        atomicWrite(destination) { out ->
            val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read selected file")
            input.use { it.copyTo(out) }
        }
        require(destination.length() > 0) { "Selected PDF is empty" }
        return destination
    }

    private fun writePdfAtomically(pdf: PdfDocument, destination: File): File =
        atomicWrite(destination) { pdf.writeTo(it) }

    private fun atomicWrite(destination: File, write: (FileOutputStream) -> Unit): File {
        val parent = destination.absoluteFile.parentFile ?: throw IOException("Destination has no parent")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create destination directory")
        val temp = File.createTempFile(".scan-", ".tmp", parent)
        try {
            FileOutputStream(temp).use(write)
            if (!temp.renameTo(destination)) throw IOException("Cannot commit output file")
            return destination
        } finally {
            temp.delete()
        }
    }

    private fun uniqueFile(directory: File, name: String): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create output directory")
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var candidate = File(directory, name)
        var suffix = 2
        while (candidate.exists()) candidate = File(directory, "$base-$suffix.${extension}").also { suffix++ }
        return candidate
    }

    private fun safeBaseName(value: String, fallback: String): String = value
        .substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
        .trim().trim('.', ' ')
        .take(80).ifBlank { fallback }

    private fun sameFile(first: File, second: File): Boolean =
        runCatching { first.canonicalFile == second.canonicalFile }
            .getOrDefault(first.absoluteFile == second.absoluteFile)
    private companion object {
        const val MAX_IMAGE_DIMENSION = 2400
        const val MAX_OCR_PAGES = 200
        const val MAX_OCR_TEXT_CHARS = 500_000
        const val MAX_OCR_LINES = 50_000
        const val MAX_OCR_INPUT_BYTES = 100L * 1024 * 1024
    }

}
