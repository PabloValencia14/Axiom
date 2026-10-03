package org.readera.openreadera.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

class Fb2Engine(context: Context) : DocumentEngine {
    private val cacheDir = context.cacheDir
    private var delegate: TxtEngine? = null
    private var temporaryFile: File? = null

    override val isAvailable: Boolean = true
    override val engineName: String = "FictionBook text reader"

    override fun open(path: String, password: String?): Boolean {
        close()
        var temp: File? = null
        return try {
            val source = File(path)
            if (!source.isFile || !source.name.endsWith(".fb2", ignoreCase = true) || source.length() > MAX_XML_BYTES) return false
            val text = source.inputStream().use(::extractBodyText)
            if (text.isBlank()) return false
            temp = File.createTempFile("fb2-", ".txt", cacheDir)
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

    private fun extractBodyText(input: InputStream): String {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(LimitedInputStream(input, MAX_XML_BYTES), null)
        }
        val output = StringBuilder()
        var bodyDepth = -1
        var captureDepth = -1
        var capture = StringBuilder()
        var rootValidated = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val depth = parser.depth
                    if (!rootValidated) {
                        rootValidated = true
                        if (!parser.name.equals("FictionBook", ignoreCase = true)) {
                            throw IllegalArgumentException("Not a FictionBook document")
                        }
                    }
                    when (parser.name) {
                        "body" -> if (bodyDepth < 0) bodyDepth = depth
                        "p", "title", "subtitle", "text-author" -> if (bodyDepth >= 0 && captureDepth < 0) {
                            captureDepth = depth
                            capture = StringBuilder()
                        }
                        "empty-line" -> if (bodyDepth >= 0 && captureDepth < 0) output.append('\n')
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (bodyDepth >= 0 && captureDepth >= 0) capture.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val depth = parser.depth
                    if (depth == captureDepth &&
                        (parser.name == "p" || parser.name == "title" ||
                            parser.name == "subtitle" || parser.name == "text-author")
                    ) {
                        val value = capture.toString().trim()
                        if (value.isNotEmpty()) output.append(value).append('\n')
                        captureDepth = -1
                    }
                    if (depth == bodyDepth && parser.name == "body") bodyDepth = -1
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
                    if (bodyDepth >= 0 && captureDepth >= 0) capture.append(entity)
                }
            }
            if (output.length > MAX_XML_BYTES) throw IllegalArgumentException("FB2 text exceeds limit")
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
            if (value >= 0 && ++count > limit) throw IllegalArgumentException("XML exceeds limit")
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val size = super.read(buffer, offset, minOf(length.toLong(), limit - count + 1).toInt())
            if (size > 0 && (count + size).also { count = it } > limit) throw IllegalArgumentException("XML exceeds limit")
            return size
        }
    }

    private companion object { const val MAX_XML_BYTES = 16L * 1024 * 1024 }
}
