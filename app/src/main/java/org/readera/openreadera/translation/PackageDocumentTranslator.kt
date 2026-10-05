package org.readera.openreadera.translation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readera.openreadera.core.io.TransferLimits
import org.readera.openreadera.core.io.copyBounded
import org.readera.openreadera.engine.DocumentFormat
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private val nativeTextExtensions = setOf("xml", "xhtml", "html", "opf", "ncx", "svg", "css")
private val unsupportedWordElements = setOf("altChunk", "object", "subDoc", "dataBinding", "ins", "del", "moveFrom", "moveTo")
private val wordTextBoundaries = setOf("tab", "ptab", "br", "cr", "softHyphen", "noBreakHyphen", "sym", "footnoteReference", "endnoteReference", "instrText", "delInstrText")
private val unsupportedSvgElements = setOf("text", "tspan", "textPath", "script", "foreignObject")
private val unsupportedXhtmlElements = setOf("script", "iframe", "object", "embed", "canvas", "input", "textarea", "select", "foreignObject")

/** Source-based native export. No provider, reader cache, indexing or publication lives here. */
class PackageDocumentTranslator(@Suppress("UNUSED_PARAMETER") context: Context) {
    suspend fun translateCopy(
        source: ResolvedTranslationSource,
        output: File,
        targetLanguage: String,
        translate: NativeTextTranslator,
        onProgress: (Int, Int) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        if (source.file.canonicalFile == output.canonicalFile) rejectNative("La traducción no puede sobrescribir el original")
        if (output.exists()) rejectNative("La copia traducida debe ser un archivo nuevo")
        if (!source.file.isFile || source.file.length() > TransferLimits.DOCUMENT) rejectNative("El original no es válido o supera 256 MiB")
        val extension = source.nativeExtension.lowercase().removePrefix(".")
        val allowed = when (source.format) {
            DocumentFormat.DOCX -> setOf("docx")
            DocumentFormat.EPUB -> setOf("epub")
            DocumentFormat.FB2 -> setOf("fb2")
            DocumentFormat.TXT -> setOf("txt", "md", "log")
            else -> rejectNative("Este formato no dispone de traducción nativa de paquetes")
        }
        if (extension !in allowed) rejectNative("La extensión nativa no corresponde al formato detectado")
        val coroutine = currentCoroutineContext()
        var ownsOutput = false
        try {
            coroutine.ensureActive()
            if (source.format == DocumentFormat.DOCX || source.format == DocumentFormat.EPUB) {
                ZipFile(source.file).use { zip ->
                    val archive = NativeArchive(zip) { coroutine.ensureActive() }
                    val plans = if (source.format == DocumentFormat.DOCX) planDocx(archive) else planEpub(archive)
                    translateUnits(plans.values.toList(), targetLanguage, translate, onProgress)
                    coroutine.ensureActive()
                    if (!output.createNewFile()) rejectNative("El archivo de salida ya existe")
                    ownsOutput = true
                    archive.write(output, plans.mapValues { it.value.bytes() }, source.format == DocumentFormat.EPUB)
                }
            } else {
                val bytes = source.file.inputStream().nativeBytes(NATIVE_TEXT_LIMIT, source.file.length()) { coroutine.ensureActive() }
                val plan = if (source.format == DocumentFormat.FB2) planFb2(bytes) else planPlainText(bytes, extension)
                translateUnits(listOf(plan), targetLanguage, translate, onProgress)
                coroutine.ensureActive()
                val translated = plan.bytes()
                if (!output.createNewFile()) rejectNative("El archivo de salida ya existe")
                ownsOutput = true
                output.outputStream().use { it.write(translated) }
            }
            coroutine.ensureActive()
        } catch (failure: Throwable) {
            if (ownsOutput && output.exists() && !output.delete()) failure.addSuppressed(java.io.IOException("Could not remove unpublished translation output"))
            throw failure
        }
    }

    private suspend fun translateUnits(plans: List<NativeEdits>, language: String, translate: NativeTextTranslator, progress: (Int, Int) -> Unit) {
        val units = plans.flatMap { it.units.sortedBy { unit -> unit.spans.first().start } }
        if (units.isEmpty()) rejectNative("El documento no contiene texto nativo narrativo compatible")
        var retained = 0L
        progress(0, units.size)
        for ((index, unit) in units.withIndex()) {
            currentCoroutineContext().ensureActive()
            val translated = translate(unit.original.trim(), language).getOrThrow()
            currentCoroutineContext().ensureActive()
            unit.accept(translated)
            retained += unit.replacement!!.length
            if (retained > NATIVE_TEXT_LIMIT) rejectNative("El texto traducido supera el límite de 16 MiB")
            progress(index + 1, units.size)
        }
    }
}

private class NativeArchive(val zip: ZipFile, val active: () -> Unit) {
    val entries: List<ZipEntry>
    private val textual = linkedMapOf<String, ByteArray>()
    init {
        if (zip.size() > NATIVE_ENTRY_LIMIT) rejectNative("El paquete supera el límite de 10.000 entradas")
        entries = zip.entries().toList()
        val seen = mutableSetOf<String>()
        var remaining = TransferLimits.DOCUMENT
        var remainingText = NATIVE_TEXT_LIMIT
        val crc = CRC32()
        val discard = object : OutputStream() {
            override fun write(value: Int) { crc.update(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { crc.update(bytes, offset, length) }
        }
        for (entry in entries) {
            active()
            crc.reset()
            if (!seen.add(entry.name) || entry.name.startsWith('/') || entry.name.contains('\\') || entry.name.split('/').any { it == ".." || it == "." } || entry.size < 0) {
                rejectNative("Entrada ZIP no válida, duplicada o sin tamaño acotado", entry.name)
            }
            if (entry.name.startsWith("_xmlsignatures/", true) || entry.name == "META-INF/signatures.xml") {
                rejectNative("No se pueden modificar paquetes con firmas digitales", entry.name)
            }
            val isText = entry.name.substringAfterLast('.').lowercase() in nativeTextExtensions || entry.name == "mimetype"
            val limit = if (isText) minOf(remaining, remainingText) else remaining
            if (entry.size > limit) rejectNative("El paquete descomprimido supera el límite de seguridad", entry.name)
            if (isText) {
                val data = zip.getInputStream(entry).nativeBytes(limit, entry.size, active)
                remaining -= data.size; remainingText -= data.size
                textual[entry.name] = data
                crc.update(data)
            } else {
                remaining -= zip.getInputStream(entry).use { copyBounded(it, discard, limit, entry.size) { active() } }
            }
            if (crc.value != entry.crc) rejectNative("La suma de comprobación ZIP no coincide", entry.name)
        }
    }
    fun bytes(name: String): ByteArray = textual[name] ?: rejectNative("Falta una parte de texto nativo necesaria", name)
    fun xml(name: String) = NativeXml(bytes(name), name)
    fun textNames(): Set<String> = textual.keys
    fun write(output: File, replacements: Map<String, ByteArray>, epub: Boolean) {
        var remaining = TransferLimits.DOCUMENT
        ZipOutputStream(output.outputStream()).use { out ->
            val ordered = if (epub) listOf(entries.first { it.name == "mimetype" }) + entries.filter { it.name != "mimetype" } else entries
            for (original in ordered) {
                active()
                val replacement = replacements[original.name]
                val entry = ZipEntry(original.name).apply {
                    method = if (epub && name == "mimetype") ZipEntry.STORED else original.method
                    if (!(epub && name == "mimetype")) time = original.time
                    comment = original.comment
                    // EPUB's mandatory first STORED mimetype has no extra field.
                    if (!(epub && name == "mimetype")) extra = original.extra
                    if (method == ZipEntry.STORED) {
                        size = replacement?.size?.toLong() ?: original.size
                        crc = replacement?.let { CRC32().apply { update(it) }.value } ?: original.crc
                    }
                }
                out.putNextEntry(entry)
                if (replacement != null) {
                    if (replacement.size > remaining) rejectNative("El paquete traducido supera 256 MiB")
                    out.write(replacement); remaining -= replacement.size
                } else {
                    remaining -= zip.getInputStream(original).use { copyBounded(it, out, remaining, original.size) { active() } }
                }
                out.closeEntry()
            }
        }
    }
}

private fun planDocx(archive: NativeArchive): Map<String, NativeEdits> {
    val types = archive.xml("[Content_Types].xml")
    if (!types.root.isName("http://schemas.openxmlformats.org/package/2006/content-types", "Types")) rejectNative("DOCX no tiene tipos de contenido válidos")
    val storyTypes = setOf("header", "footer", "footnotes", "endnotes", "comments")
    val storyFiles = types.elements.filter { it.name == "Override" &&
        storyTypes.any { type -> it.attr("ContentType") == "application/vnd.openxmlformats-officedocument.wordprocessingml.$type+xml" }
    }.map { packagePath("", it.attr("PartName").removePrefix("/")) }.toSet() + "word/document.xml"
    for (name in storyFiles) if (archive.zip.getEntry(name) == null) rejectNative("Falta una parte de texto DOCX", name)
    if (archive.zip.getEntry("word/document.xml") == null) rejectNative("Falta la parte principal del documento DOCX")
    val plans = linkedMapOf<String, NativeEdits>()
    for (name in archive.textNames().filter { (it.startsWith("word/") || it in storyFiles) && it.endsWith(".xml") }) {
        archive.active()
        val xml = archive.xml(name)
        if (name == "word/document.xml" && !xml.root.isName(WORD_NS, "document")) rejectNative("Espacio de nombres o raíz DOCX no compatible", name)
        for (element in xml.elements) {
            if (element.namespace == WORD_NS && element.name in unsupportedWordElements ||
                element.name == "AlternateContent" && element.descendants().any { child -> (child.isName(WORD_NS, "t") || child.isName(DRAWING_NS, "t")) && child.text.any { span -> span.text.any(Char::isLetter) } } ||
                element.name == "textpath" && element.attr("string").any { it.isLetter() } ||
                element.namespace.contains("/chart") && (element.name == "chart" || element.name == "v") && element.text.any { span -> span.text.any { it.isLetter() } } ||
                element.namespace.contains("/diagram") && element.name == "t") {
                rejectNative("Contenido DOCX visible ambiguo o no compatible; no se ha traducido el documento", name)
            }
        }
        val story = name in storyFiles || name.startsWith("word/drawings/") || name.startsWith("word/diagrams/") || name.startsWith("word/charts/")
        if (!story) {
            if (xml.elements.any { (it.isName(WORD_NS, "t") || it.isName(DRAWING_NS, "t")) && it.text.any { span -> span.text.any(Char::isLetter) } }) {
                rejectNative("Hay texto visible DOCX en una parte nativa no compatible", name)
            }
            continue
        }
        val fieldResults = mutableListOf<Boolean>()
        val group = mutableListOf<NativeSpan>()
        val styles = mutableMapOf<NativeXml.XmlElement, String>()
        var key: String? = null
        fun flush() { xml.edits.add(group.toList(), ::escapeXml); group.clear(); key = null }
        for (element in xml.elements) {
            if (element.isName(WORD_NS, "fldChar")) {
                flush()
                when (element.element.getAttributeNS(WORD_NS, "fldCharType")) {
                    "begin" -> fieldResults += false
                    "separate" -> { if (fieldResults.isEmpty()) rejectNative("Campo DOCX sin inicio", name); fieldResults[fieldResults.lastIndex] = true }
                    "end" -> { if (fieldResults.isEmpty()) rejectNative("Campo DOCX sin cierre", name); fieldResults.removeAt(fieldResults.lastIndex) }
                }
            }
            if ((element.namespace == WORD_NS && element.name in wordTextBoundaries) ||
                element.isName(DRAWING_NS, "br")) flush()
            if (!(element.isName(WORD_NS, "t") || element.isName(DRAWING_NS, "t"))) continue
            if (element.text.none { span -> span.text.any(Char::isLetter) } && element.text.any { span -> span.text.any(Char::isDigit) }) {
                flush(); continue
            }
            val ancestors = element.ancestors().toList()
            val simpleField = ancestors.any { it.isName(WORD_NS, "fldSimple") || it.isName(DRAWING_NS, "fld") }
            val complexFieldResult = fieldResults.lastOrNull() == true
            if ((simpleField || complexFieldResult) && element.text.any { span -> span.text.any(Char::isLetter) }) {
                rejectNative("El resultado visible de un campo DOCX contiene texto no compatible", name)
            }
            val run = ancestors.firstOrNull { it.isName(WORD_NS, "r") || it.isName(DRAWING_NS, "r") }
            if (fieldResults.isNotEmpty() || simpleField || ancestors.any { it.isName(WORD_NS, "del") } ||
                run?.children?.any { it.isName(WORD_NS, "rPr") && it.children.any { prop -> (prop.name == "vanish" || prop.name == "webHidden") && prop.element.getAttributeNS(WORD_NS, "val").let { value -> value != "0" && value != "false" && value != "off" } } } == true) {
                flush(); continue
            }
            val paragraph = ancestors.firstOrNull { it.isName(WORD_NS, "p") || it.isName(DRAWING_NS, "p") }
            val boundary = ancestors.filter { it !== run && it !== paragraph }.joinToString("/") { it.start.toString() }
            val style = run?.let { styles.getOrPut(it) { it.children.firstOrNull { child -> child.name == "rPr" }?.let(xml::styleKey).orEmpty() } }.orEmpty()
            val newKey = "${element.namespace}:${paragraph?.start}:$boundary:$style"
            if (newKey != key) flush()
            key = newKey
            group.addAll(element.text)
        }
        flush()
        if (fieldResults.isNotEmpty()) rejectNative("Campo DOCX sin cierre", name)
        if (xml.edits.units.isNotEmpty()) plans[name] = xml.edits
    }
    return plans
}

private fun packagePath(base: String, reference: String): String {
    val uri = try { URI(reference) } catch (_: Exception) { rejectNative("Invalid package resource URI", reference) }
    if (uri.isAbsolute || uri.rawAuthority != null || uri.query != null || uri.fragment != null || reference.contains('\\')) rejectNative("Nonlocal package resource URI", reference)
    val resolved = URI("/" + base).resolve(uri).normalize().path.removePrefix("/")
    if (resolved.isBlank() || resolved.split('/').any { it == ".." || it == "." } || resolved.contains('\\')) rejectNative("Unsafe package resource URI", reference)
    return resolved
}

private fun planEpub(archive: NativeArchive): Map<String, NativeEdits> {
    if (!archive.bytes("mimetype").contentEquals("application/epub+zip".toByteArray())) rejectNative("El tipo MIME de EPUB no es válido")
    if (archive.zip.getEntry("META-INF/encryption.xml") != null) {
        val encryption = archive.xml("META-INF/encryption.xml")
        val namespace = "http://www.w3.org/2001/04/xmlenc#"
        val data = encryption.elements.filter { it.isName(namespace, "EncryptedData") }
        if (data.isEmpty()) rejectNative("Metadatos de cifrado EPUB ambiguos")
        val contents = encryption.elements.groupBy { element -> element.ancestors().firstOrNull { it.isName(namespace, "EncryptedData") } }
        for (entry in data) {
            val children = contents[entry].orEmpty()
            val methods = children.filter { it.isName(namespace, "EncryptionMethod") }
            val references = children.filter { it.isName(namespace, "CipherReference") }
            val algorithm = methods.singleOrNull()?.attr("Algorithm")
            if (algorithm != "http://www.idpf.org/2008/embedding" && algorithm != "http://ns.adobe.com/pdf/enc#RC") {
                rejectNative("El EPUB contiene cifrado de texto o DRM no compatible")
            }
            val resource = references.singleOrNull()?.attr("URI") ?: rejectNative("Referencia de cifrado EPUB ambigua")
            val path = packagePath("", resource)
            val extension = path.substringAfterLast('.').lowercase()
            if ((extension != "otf" && extension != "ttf" && extension != "woff" && extension != "woff2") || archive.zip.getEntry(path) == null) {
                rejectNative("Solo se pueden conservar fuentes EPUB ofuscadas sin modificar", path)
            }
        }
    }
    val container = archive.xml("META-INF/container.xml")
    val rootfiles = container.elements.filter { it.name == "rootfile" && it.namespace == "urn:oasis:names:tc:opendocument:xmlns:container" }
    if (rootfiles.size != 1) rejectNative("El EPUB necesita un único paquete raíz inequívoco")
    val opfName = packagePath("", rootfiles.single().attr("full-path"))
    val opf = archive.xml(opfName)
    val items = opf.elements.filter { it.name == "item" && it.namespace == "http://www.idpf.org/2007/opf" }
    if (items.map { it.attr("id") }.toSet().size != items.size || items.any { it.attr("id").isBlank() }) rejectNative("Identificadores EPUB ausentes o duplicados")
    val manifest = items.associateBy { it.attr("id") }
    val spine = opf.elements.filter { it.name == "itemref" && it.namespace == "http://www.idpf.org/2007/opf" }
    if (spine.isEmpty() || spine.size > 2_000) rejectNative("El índice de lectura EPUB falta o supera 2.000 entradas")
    for (item in spine) {
        val entry = manifest[item.attr("idref")] ?: rejectNative("Falta una referencia del índice de lectura EPUB")
        if (entry.attr("media-type") != "application/xhtml+xml") rejectNative("El índice EPUB contiene texto visible no XHTML no compatible")
    }
    val selected = linkedSetOf<String>()
    for (item in items) {
        val media = item.attr("media-type")
        val path = packagePath(opfName, item.attr("href"))
        if (archive.zip.getEntry(path) == null) rejectNative("Falta un recurso del manifiesto EPUB", path)
        if (media == "application/xhtml+xml" || media == "application/x-dtbncx+xml") selected += path
        if (media == "text/html" || item.attr("properties").split(' ').contains("scripted")) rejectNative("Recurso EPUB visible o con scripts no compatible", path)
        if (media == "image/svg+xml") {
            val svg = archive.xml(path)
            if (svg.elements.any { it.name == "script" || it.name == "foreignObject" ||
                    it.name in unsupportedSvgElements && it.text.any { span -> span.text.any(Char::isLetter) } }) {
                rejectNative("Las imágenes SVG con texto nativo visible o scripts no son compatibles", path)
            }
        }
        if (media == "text/css") {
            val css = NativeEncoding.decode(archive.bytes(path)).text
            if (Regex("content\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(css)) rejectNative("El texto visible generado por CSS no es compatible", path)
        }
    }
    val plans = linkedMapOf<String, NativeEdits>()
    for (name in selected) {
        archive.active()
        val xml = archive.xml(name)
        val ncx = xml.root.namespace == "http://www.daisy.org/z3986/2005/ncx/"
        if (!ncx && !xml.root.isName(XHTML_NS, "html")) rejectNative("El contenido EPUB no es XHTML con espacio de nombres", name)
        for (element in xml.elements) {
            if (!ncx && (element.name in unsupportedXhtmlElements ||
                    (element.namespace != XHTML_NS && element.namespace.isNotEmpty()) && element.text.any { it.text.any(Char::isLetter) } ||
                    element.isName(XHTML_NS, "style") && Regex("content\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(element.text.joinToString("") { it.text }))) {
                rejectNative("Contenido EPUB visible no compatible; no se ha traducido el documento", name)
            }
            val translate = if (ncx) element.name == "text" && element.namespace == xml.root.namespace else
                (element.isName(XHTML_NS, "title") || (element.isName(XHTML_NS, "body") || element.ancestors().any { it.isName(XHTML_NS, "body") })) &&
                    element.name != "style" && element.name != "script" && element.ancestors().none { it.name == "style" || it.name == "script" }
            if (translate) element.text.forEach { xml.edits.add(listOf(it), ::escapeXml) }
        }
        if (xml.edits.units.isNotEmpty()) plans[name] = xml.edits
    }
    return plans
}

private fun planFb2(bytes: ByteArray): NativeEdits {
    val xml = NativeXml(bytes, "FB2")
    if (!xml.root.isName(FB2_NS, "FictionBook")) rejectNative("El documento no es FictionBook con espacio de nombres")
    val narrative = setOf("body", "annotation", "book-title")
    for (element in xml.elements) {
        val chosen = (element.namespace == FB2_NS && element.name in narrative) ||
            element.ancestors().any { it.namespace == FB2_NS && it.name in narrative }
        if (!chosen) continue
        if (element.namespace != FB2_NS || element.name == "binary" || element.name == "stylesheet") rejectNative("Contenido narrativo FB2 no compatible")
        element.text.forEach { xml.edits.add(listOf(it), ::escapeXml) }
    }
    return xml.edits
}
