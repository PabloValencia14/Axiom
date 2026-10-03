package org.readera.openreadera.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipFile

class DocxEngine(context: Context) : DocumentEngine {
    private val cacheDir = context.cacheDir
    private var delegate: TxtEngine? = null
    private var temporaryFile: File? = null

    override val isAvailable: Boolean = true
    override val engineName: String = "DOCX text reader"

    override fun open(path: String, password: String?): Boolean {
        close()
        var temp: File? = null
        return try {
            val text = ZipFile(path).use { zip ->
                val entry = zip.getEntry("word/document.xml") ?: return false
                zip.getInputStream(entry).use(::extractDocumentText)
            }
            if (text.isBlank()) return false
            temp = File.createTempFile("docx-", ".txt", cacheDir)
            temp.writeText(text, Charsets.UTF_8)
            val engine = TxtEngine()
            if (!engine.open(temp.absolutePath) || engine.getPageCount() == 0) {
                engine.close()
                temp.delete()
                false
            } else {
                temporaryFile = temp
                delegate = engine
                true
            }
        } catch (_: Exception) {
            temp?.delete()
            false
        }
    }

    private fun extractDocumentText(input: InputStream): String {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(LimitedInputStream(input, MAX_XML_BYTES.toLong()), null)
        }
        val output = StringBuilder()
        var depth = 0
        var paragraphDepth = -1
        var inParagraph = false
        var hasCellInRow = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name) {
                        "tr" -> hasCellInRow = false
                        "p" -> { inParagraph = true; paragraphDepth = depth }
                        "tc" -> {
                            if (hasCellInRow) {
                                if (output.isNotEmpty() && output.last() == '\n') output.deleteCharAt(output.lastIndex)
                                output.append('\t')
                            }
                            hasCellInRow = true
                        }
                        "tab" -> if (inParagraph) output.append('\t')
                        "br", "cr" -> if (inParagraph) output.append('\n')
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inParagraph) output.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (parser.name == "p" && inParagraph && paragraphDepth == depth) {
                        output.append('\n')
                        inParagraph = false
                        paragraphDepth = -1
                    }
                    depth--
                }
                XmlPullParser.DOCDECL -> throw IllegalArgumentException("Declarations are not accepted")
                XmlPullParser.ENTITY_REF -> {
                    val entity = when (parser.name) {
                        "amp" -> "&"
                        "lt" -> "<"
                        "gt" -> ">"
                        "apos" -> "'"
                        "quot" -> "\""
                        else -> throw IllegalArgumentException("Unknown XML entity")
                    }
                    if (inParagraph) output.append(entity)
                }
            }
            if (output.length > MAX_TEXT_BYTES) throw IllegalArgumentException("DOCX text exceeds limit")
            event = parser.nextToken()
        }
        return output.toString().trim()
    }

    override fun getPageCount() = delegate?.getPageCount() ?: 0
    override fun getPageSize(pageIndex: Int) = delegate?.getPageSize(pageIndex) ?: PageSize(0f, 0f)
    override fun renderPage(pageIndex: Int, targetBitmap: Bitmap, options: RenderOptions) = delegate?.renderPage(pageIndex, targetBitmap, options) ?: false
    override fun getPageText(pageIndex: Int) = delegate?.getPageText(pageIndex) ?: ""
    override fun getPageTextLayout(pageIndex: Int) = delegate?.getPageTextLayout(pageIndex) ?: PageTextLayout("", emptyList())
    override fun getPageTextRange(pageIndex: Int) = delegate?.getPageTextRange(pageIndex)
    override fun getPageTextLineBounds(pageIndex: Int) = delegate?.getPageTextLineBounds(pageIndex) ?: emptyList()
    override fun getOutline() = delegate?.getOutline() ?: emptyList()
    override fun search(query: String) = delegate?.search(query) ?: emptyList()
    override fun applyOptions(options: RenderOptions) = delegate?.applyOptions(options) ?: 0

    override fun close() {
        try { delegate?.close() } finally {
            delegate = null
            temporaryFile?.delete()
            temporaryFile = null
        }
    }

    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            val value = super.read()
            if (value >= 0 && ++count > limit) throw IllegalArgumentException("DOCX XML exceeds limit")
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val size = super.read(buffer, offset, minOf(length.toLong(), limit - count + 1).toInt())
            if (size > 0 && (count + size).also { count = it } > limit) throw IllegalArgumentException("DOCX XML exceeds limit")
            return size
        }
    }

    private companion object { const val MAX_XML_BYTES = 16 * 1024 * 1024; const val MAX_TEXT_BYTES = 16 * 1024 * 1024 }
}
