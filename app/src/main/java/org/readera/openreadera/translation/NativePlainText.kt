package org.readera.openreadera.translation

internal fun planPlainText(bytes: ByteArray, extension: String): NativeEdits {
    val encoding = NativeEncoding.decode(bytes)
    val edits = NativeEdits(encoding, extension)
    val text = encoding.text
    if (text.any { it.code < 32 && it != '\r' && it != '\n' && it != '\t' }) rejectNative("Los datos binarios o de control no son texto nativo")
    val lines = Regex("[^\\r\\n]+|\\r\\n|[\\r\\n]").findAll(text)
    var fence: String? = null
    val references = if (extension == "md") markdownReferences(text) else emptySet()
    if (extension == "md" && text.startsWith("---") && Regex("(?m)^---[ \\t]*$").findAll(text).take(2).count() >= 2) {
        rejectNative("Los metadatos iniciales Markdown necesitan un editor específico")
    }
    for (line in lines) {
        val value = line.value
        if (value == "\r" || value == "\n" || value == "\r\n" || value.isBlank()) continue
        val start = line.range.first
        when (extension) {
            "log" -> planLogLine(value, start, edits)
            "md" -> {
                val marker = markdownFence.matchEntire(value)
                if (fence != null) {
                    if (marker != null && marker.groupValues[1].first() == fence.first() &&
                        marker.groupValues[1].length >= fence.length && marker.groupValues[2].isBlank()) fence = null
                    continue
                }
                if (marker != null) { fence = marker.groupValues[1]; continue }
                val definition = markdownDefinition.find(value)?.groupValues?.get(1)
                if (value.startsWith("    ") || value.startsWith('\t') || (definition != null && !definition.startsWith('^')) ||
                    Regex("^[ |:=-]+$").matches(value)) continue
                planMarkdownLine(value, start, edits, references)
            }
            else -> {
                if (value.trimStart().startsWith('{') || value.trimStart().startsWith('[') ||
                    Regex("^\\s*<[/!?A-Za-z]").containsMatchIn(value)) {
                    rejectNative("El TXT contiene datos estructurados ambiguos; traducirlos alteraría campos de máquina")
                }
                Regex("[^\\t]+").findAll(value).forEach { span ->
                    edits.add(listOf(NativeSpan(start + span.range.first, start + span.range.last + 1, span.value)), singleLine = true)
                }
            }
        }
    }
    if (fence != null) rejectNative("Bloque de código Markdown sin cierre")
    return edits
}

private val logPrefix = Regex("""^(?:\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?(?:Z|[+-]\d{2}:?\d{2})?)[ \t]+(?:\[(?:TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\]|TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)(?:[ \t]+\[[^]\r\n]*])*[ \t]+""")
private val machineSpan = Regex("""\b[A-Za-z_][\w.-]*=(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\S+)|(?:https?://|www\.)\S+|(?:[A-Za-z]:\\|/)[^\s]+|\b[\w.+-]+@[\w.-]+\b|\b\d+(?:[.:/-]\d+)*\b""")
private val quotedField = Regex("""^"(?:\\.|[^"\\])*"$|^'(?:\\.|[^'\\])*'$""")
private val logMachineToken = Regex("""\b(?:0x[0-9a-fA-F]+|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}|[A-Za-z0-9_.-]*[0-9_][A-Za-z0-9_.-]*|(?:[A-Za-z_]\w*\.)+[A-Za-z_]\w*)\b""")

private fun planLogLine(value: String, start: Int, edits: NativeEdits) {
    val prefix = logPrefix.find(value) ?: rejectNative("Registro ambiguo: se requiere fecha/hora, nivel y mensaje")
    val message = value.substring(prefix.value.length)
    val protected = BooleanArray(value.length)
    for (index in 0 until prefix.value.length) protected[index] = true
    machineSpan.findAll(message).forEach { match ->
        val fieldValue = match.value.substringAfter('=', "")
        if ((fieldValue.startsWith('"') || fieldValue.startsWith('\'')) && !quotedField.matches(fieldValue)) {
            rejectNative("Campo de registro entre comillas sin cierre")
        }
        for (index in match.range) protected[prefix.value.length + index] = true
    }
    logMachineToken.findAll(message).forEach { match ->
        for (index in match.range) protected[prefix.value.length + index] = true
    }
    val narrative = buildString(message.length) {
        for (index in message.indices) append(if (protected[prefix.value.length + index]) ' ' else message[index])
    }
    if (narrative.any { it in "{}[]<>\t|" } || Regex("(?:^|\\s)[A-Za-z_][\\w.-]*:").containsMatchIn(narrative)) {
        rejectNative("Los mensajes de registro estructurados necesitan un editor específico de su esquema")
    }
    addUnprotected(value, start, protected, edits) { translated ->
        if (translated.any { it in "{}[]<>\t|=" } ||
            Regex("(?:^|\\s)[A-Za-z_][\\w.-]*:").containsMatchIn(translated)) rejectNative("La traducción introduce sintaxis de campos de máquina en el registro")
        translated
    }
}

private val markdownDefinition = Regex("^ {0,3}\\[([^]\\r\\n]+)]:")
private val markdownFence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
private fun referenceKey(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)

private fun markdownReferences(text: String): Set<String> {
    val references = mutableSetOf<String>()
    var fence: String? = null
    for (line in Regex("[^\\r\\n]+").findAll(text)) {
        val marker = markdownFence.matchEntire(line.value)
        if (fence != null) {
            if (marker != null && marker.groupValues[1].first() == fence.first() &&
                marker.groupValues[1].length >= fence.length && marker.groupValues[2].isBlank()) fence = null
            continue
        }
        if (marker != null) { fence = marker.groupValues[1]; continue }
        if (line.value.startsWith("    ") || line.value.startsWith('\t')) continue
        markdownDefinition.find(line.value)?.groupValues?.get(1)?.takeUnless { it.startsWith('^') }?.let { references += referenceKey(it) }
    }
    return references
}

private fun planMarkdownLine(value: String, start: Int, edits: NativeEdits, references: Set<String>) {
    val protected = BooleanArray(value.length)
    fun protect(from: Int, until: Int) { for (index in from until until) protected[index] = true }
    val brackets = mutableListOf<Int>()
    Regex("^ {0,3}[-*+] \\[[ xX]]").find(value)?.let { protect(it.range.first, it.range.last + 1) }
    var index = 0
    while (index < value.length) {
        if (protected[index]) { index++; continue }
        when (value[index]) {
            '\\' -> { protect(index, minOf(value.length, index + 2)); index += 2 }
            '`' -> {
                val endMarker = value.indexOfFirstFrom(index) { it != '`' }
                val count = endMarker - index
                val marker = "`".repeat(count)
                var close = value.indexOf(marker, endMarker)
                while (close >= 0 && (value.getOrNull(close - 1) == '`' || value.getOrNull(close + count) == '`')) close = value.indexOf(marker, close + count)
                if (close < 0) rejectNative("Código Markdown en línea sin cierre")
                protect(index, close + count); index = close + count
            }
            '<' -> {
                val end = value.indexOf('>', index + 1)
                if (end < 0 || !Regex("(?:https?://[^ >]+|mailto:[^ >]+|[^ <>]+@[^ <>]+)").matches(value.substring(index + 1, end))) {
                    rejectNative("El HTML incrustado en Markdown no es compatible")
                }
                protect(index, end + 1); index = end + 1
            }
            '[' -> { brackets += index; protected[index] = true; index++ }
            ']' -> {
                protected[index] = true
                val opening = if (brackets.isEmpty()) null else brackets.removeAt(brackets.lastIndex)
                val label = opening?.let { value.substring(it + 1, index) }
                if (opening != null && label?.startsWith('^') == true) {
                    protect(opening, index + 1)
                    if (value.getOrNull(index + 1) == ':') { protect(index + 1, index + 2); index += 2 } else index++
                    continue
                }
                if (value.getOrNull(index + 1) == '(') {
                    var cursor = index + 2
                    var depth = 1
                    var quote: Char? = null
                    var angle = false
                    while (cursor < value.length && depth > 0) {
                        val char = value[cursor]
                        when {
                            char == '\\' -> cursor++
                            quote != null -> if (char == quote) quote = null
                            char in "\"'" && value.getOrNull(cursor - 1)?.isWhitespace() == true -> quote = char
                            char == '<' -> angle = true
                            char == '>' -> angle = false
                            !angle && char == '(' -> depth++
                            !angle && char == ')' -> depth--
                        }
                        cursor++
                    }
                    if (depth != 0 || quote != null || angle) rejectNative("Destino de enlace Markdown sin cierre")
                    protect(index + 1, cursor)
                    index = cursor
                } else if (value.getOrNull(index + 1) == '[') {
                    val end = value.indexOf(']', index + 2)
                    if (end < 0) rejectNative("Referencia de enlace Markdown sin cierre")
                    if (end == index + 2) {
                        if (label == null || referenceKey(label) !in references) rejectNative("Enlace Markdown implícito sin una definición inequívoca")
                        edits.fixedEdits += Triple(start + index + 2, start + index + 2, label)
                    } else if (referenceKey(value.substring(index + 2, end)) !in references) {
                        rejectNative("Definición de destino Markdown no encontrada")
                    }
                    protect(index + 1, end + 1)
                    index = end + 1
                } else {
                    if (label != null && referenceKey(label) in references) {
                        edits.fixedEdits += Triple(start + index + 1, start + index + 1, "[$label]")
                    }
                    index++
                }
            }
            '$' -> rejectNative("La sintaxis matemática Markdown necesita un editor específico")
            else -> {
                if (value[index] in "*_#>~|[!{}()" || value[index] == '\t') protected[index] = true
                index++
            }
        }
    }
    Regex("^ {0,3}(?:[-+] |\\d+[.)] )").find(value)?.let { protect(it.range.first, it.range.last + 1) }
    Regex("&(?:#[0-9]+|#x[0-9a-fA-F]+|[A-Za-z]+);").findAll(value).forEach { protect(it.range.first, it.range.last + 1) }
    Regex("\\{(?:[#.:][^}]*|:[^}]*)}").findAll(value).forEach { protect(it.range.first, it.range.last + 1) }
    machineSpan.findAll(value).forEach { protect(it.range.first, it.range.last + 1) }
    addUnprotected(value, start, protected, edits) { translated ->
        buildString { for (char in translated) { if (char in "\\`*_{}[]()#+-.!>|~<$&") append('\\'); append(char) } }
    }
}

private fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
    for (index in start until length) if (predicate(this[index])) return index
    return length
}

private fun addUnprotected(value: String, start: Int, protected: BooleanArray, edits: NativeEdits, escape: (String) -> String) {
    var cursor = 0
    while (cursor < value.length) {
        if (protected[cursor]) { cursor++; continue }
        val from = cursor
        while (cursor < value.length && !protected[cursor]) cursor++
        edits.add(listOf(NativeSpan(start + from, start + cursor, value.substring(from, cursor))), escape, singleLine = true)
    }
}
