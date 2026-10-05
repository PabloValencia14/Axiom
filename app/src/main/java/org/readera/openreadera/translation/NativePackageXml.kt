package org.readera.openreadera.translation

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
internal const val DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
internal const val XHTML_NS = "http://www.w3.org/1999/xhtml"
internal const val FB2_NS = "http://www.gribuser.ru/xml/fictionbook/2.0"

/** XML is validated by a namespace-aware parser; edits touch only original character spans. */
internal class NativeXml(bytes: ByteArray, val location: String) {
    class XmlElement(val element: Element, val start: Int, val parent: XmlElement?) {
        val children = mutableListOf<XmlElement>()
        val text = mutableListOf<NativeSpan>()
        val name: String get() = element.localName ?: element.tagName
        val namespace: String get() = element.namespaceURI.orEmpty()
        fun attr(name: String): String = element.getAttribute(name)
        fun isName(namespace: String, name: String) = this.namespace == namespace && this.name == name
        fun ancestors(): Sequence<XmlElement> = generateSequence(parent) { it.parent }
        fun descendants(): Sequence<XmlElement> = children.asSequence().flatMap { sequenceOf(it) + it.descendants() }
    }

    val encoding = NativeEncoding.decode(bytes, xml = true)
    val edits = NativeEdits(encoding, location)
    val elements = mutableListOf<XmlElement>()
    val root: XmlElement

    init {
        val source = encoding.text
        // Scan before constructing a DOM: true DTD/entity declarations are rejected, not comment/CDATA text.
        val tokens = scan(source)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        // scan() rejects declarations before parsing; user-controlled entities cannot reach the parser.
        // Resolve defensively as well on Android parsers that do not implement the JAXP feature flags.
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External XML entities are forbidden") }
            setErrorHandler(object : DefaultHandler() {
                override fun error(e: org.xml.sax.SAXParseException) { throw e }
                override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
            })
        }
        val document = try {
            builder.parse(InputSource(StringReader(source)))
        } catch (failure: SAXException) {
            throw TranslationRejectedException("XML no válido; no se ha traducido el documento", location).apply { initCause(failure) }
        }
        val domElements = mutableListOf<Element>()
        fun collect(node: Node) {
            if (node is Element) domElements += node
            var child = node.firstChild
            while (child != null) { collect(child); child = child.nextSibling }
        }
        collect(document.documentElement)
        val stack = mutableListOf<XmlElement>()
        var next = 0
        for (token in tokens) {
            when (token.kind) {
                1 -> {
                    val element = XmlElement(domElements[next++], token.start, parent = stack.lastOrNull())
                    element.parent?.children?.add(element)
                    elements += element
                    if (!token.selfClosing) stack += element
                }
                2 -> stack.removeAt(stack.lastIndex)
                3 -> stack.lastOrNull()?.text?.add(NativeSpan(token.start, token.end, decodeEntities(source.substring(token.start, token.end))))
                4 -> stack.lastOrNull()?.text?.add(NativeSpan(token.start, token.end, source.substring(token.start + 9, token.end - 3).replace("\r\n", "\n").replace('\r', '\n')))
            }
        }
        check(next == domElements.size && stack.isEmpty())
        root = elements.first()
    }

    fun styleKey(element: XmlElement): String = buildString {
        append(element.namespace).append(':').append(element.name)
        val attributes = element.element.attributes
        val values = (0 until attributes.length).map { attributes.item(it) }
            .filter { it.namespaceURI != "http://www.w3.org/2000/xmlns/" }
            .map { "${it.namespaceURI.orEmpty()}:${it.localName ?: it.nodeName}=${it.nodeValue.length}:${it.nodeValue}" }.sorted()
        for (value in values) append(value.length).append(':').append(value)
        for (child in element.children) {
            val key = styleKey(child)
            append(key.length).append(':').append(key)
        }
    }

    private data class Token(val start: Int, val end: Int, val kind: Int, val selfClosing: Boolean = false)
    private fun scan(source: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var offset = 0
        var depth = 0
        var nodeCount = 0
        while (offset < source.length) {
            nodeCount++
            val start = offset
            if (source[offset] != '<') {
                offset = source.indexOf('<', offset).takeIf { it >= 0 } ?: source.length
                tokens += Token(start, offset, 3)
            } else if (source.startsWith("<!--", offset)) {
                offset = source.indexOf("-->", offset + 4).takeIf { it >= 0 }?.plus(3) ?: rejectNative("Comentario XML sin cierre", location)
            } else if (source.startsWith("<![CDATA[", offset)) {
                offset = source.indexOf("]]>", offset + 9).takeIf { it >= 0 }?.plus(3) ?: rejectNative("Sección CDATA XML sin cierre", location)
                tokens += Token(start, offset, 4)
            } else if (source.startsWith("<?", offset)) {
                offset = source.indexOf("?>", offset + 2).takeIf { it >= 0 }?.plus(2) ?: rejectNative("Instrucción XML sin cierre", location)
            } else {
                if (source.startsWith("<!", offset)) rejectNative("Declaración XML no compatible", location)
                var quote: Char? = null
                offset++
                while (offset < source.length) {
                    val char = source[offset++]
                    if (quote != null) { if (char == quote) quote = null }
                    else if (char == '\'' || char == '"') quote = char
                    else if (char == '=') nodeCount++
                    else if (char == '>') break
                }
                if (offset > source.length || source.getOrNull(offset - 1) != '>') rejectNative("Etiqueta XML sin cierre", location)
                val closing = source.startsWith("</", start)
                val selfClosing = source.getOrNull(offset - 2) == '/'
                tokens += Token(start, offset, if (closing) 2 else 1, selfClosing)
                if (closing) depth-- else if (!selfClosing) depth++
                if (depth !in 0..128) rejectNative("El anidamiento XML supera el límite de 128 niveles", location)
            }
            if (nodeCount > 200_000) rejectNative("El XML supera el límite de 200.000 nodos y atributos", location)
        }
        return tokens
    }
}

internal fun escapeXml(value: String): String {
    var size = 0L
    for (char in value) size += when (char) { '&' -> 5; '<', '>' -> 4; else -> 1 }
    if (size > NATIVE_TEXT_LIMIT) rejectNative("El texto XML traducido supera el límite de 16 MiB")
    return buildString(size.toInt()) {
        for (char in value) when (char) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); else -> append(char) }
    }
}

private fun decodeEntities(value: String): String = Regex("&([^;]+);").replace(value.replace("\r\n", "\n").replace('\r', '\n')) { match ->
    when (val entity = match.groupValues[1]) {
        "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "apos" -> "'"; "quot" -> "\""
        else -> {
            val code = when {
                entity.startsWith("#x") -> entity.substring(2).toIntOrNull(16)
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()
                else -> null
            } ?: rejectNative("Entidad XML desconocida")
            if (!Character.isValidCodePoint(code)) rejectNative("Carácter XML no válido")
            String(Character.toChars(code))
        }
    }
}
