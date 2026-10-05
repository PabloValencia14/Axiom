package org.readera.openreadera.translation

import org.readera.openreadera.core.io.copyBounded
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal const val NATIVE_TEXT_LIMIT = 16L * 1024 * 1024
internal const val NATIVE_ENTRY_LIMIT = 10_000

internal fun rejectNative(message: String, location: String? = null): Nothing =
    throw TranslationRejectedException(message, location)

internal fun InputStream.nativeBytes(limit: Long, size: Long = -1, active: () -> Unit = {}): ByteArray {
    if (size > limit) rejectNative("El documento nativo supera el límite de bytes")
    return use { input ->
        val bytes = ByteArrayOutputStream()
        try {
            copyBounded(input, bytes, limit, size.takeIf { it >= 0 }) { active() }
        } catch (failure: IllegalArgumentException) {
            throw TranslationRejectedException("El flujo nativo está truncado o supera el límite de seguridad").apply { initCause(failure) }
        }
        bytes.toByteArray()
    }
}

/** Strict decoding: no replacement characters and no guess at legacy encodings. */
internal class NativeEncoding private constructor(val text: String, private val charset: Charset, private val bom: ByteArray) {
    fun encode(value: String): ByteArray {
        val buffer = try {
            charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw TranslationRejectedException("La traducción no se puede representar en la codificación original ${charset.name()}").apply { initCause(failure) }
        }
        if (buffer.remaining().toLong() + bom.size > NATIVE_TEXT_LIMIT) rejectNative("El texto traducido supera el límite de 16 MiB")
        return ByteArray(bom.size + buffer.remaining()).also { bytes ->
            bom.copyInto(bytes)
            buffer.get(bytes, bom.size, bytes.size - bom.size)
        }
    }

    companion object {
        fun decode(bytes: ByteArray, xml: Boolean = false): NativeEncoding {
            if (bytes.size > NATIVE_TEXT_LIMIT) rejectNative("El texto original supera el límite de 16 MiB")
            val prefix = when {
                bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> 3
                bytes.size >= 2 && ((bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) ||
                    (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte())) -> 2
                else -> 0
            }
            val detected = when {
                prefix == 2 -> if (bytes[0] == 0xff.toByte()) Charsets.UTF_16LE else Charsets.UTF_16BE
                xml && bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte() -> Charsets.UTF_16BE
                xml && bytes.size >= 4 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte() -> Charsets.UTF_16LE
                else -> Charsets.UTF_8
            }
            val declaration = Regex("""^<\?xml\s[^?]*encoding\s*=\s*['"]([^'"]+)['"]""")
            // An ASCII-compatible XML declaration is authoritative, unlike guessing a TXT charset.
            val asciiDeclaration = if (xml && prefix == 0 && detected == Charsets.UTF_8)
                declaration.find(String(bytes, 0, minOf(bytes.size, 4096), Charsets.US_ASCII))?.groupValues?.get(1) else null
            val charset = asciiDeclaration?.let {
                try { Charset.forName(it) } catch (_: IllegalArgumentException) { rejectNative("Codificación XML no compatible: $it") }
            } ?: detected
            val text = try {
                charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, prefix, bytes.size - prefix)).toString()
            } catch (failure: java.nio.charset.CharacterCodingException) {
                throw TranslationRejectedException("Codificación no válida; se requiere UTF-8, UTF-16 con BOM o una declaración XML compatible").apply { initCause(failure) }
            }
            if (text.any { it == '\u0000' }) rejectNative("Caracteres NUL o codificación no compatible")
            if (xml) {
                val declared = declaration.find(text)?.groupValues?.get(1)
                val compatible = declared == null || (declared.equals("UTF-16", true) && charset.name().startsWith("UTF-16")) ||
                    try { Charset.forName(declared) == charset } catch (_: IllegalArgumentException) { false }
                if (!compatible) rejectNative("La declaración XML no coincide con su codificación original")
            }
            return NativeEncoding(text, charset, bytes.copyOfRange(0, prefix))
        }
    }
}

internal data class NativeSpan(val start: Int, val end: Int, val text: String)
internal class NativeUnit(val spans: List<NativeSpan>, val escape: (String) -> String = { it }, val singleLine: Boolean = false) {
    val original = spans.joinToString("") { it.text }
    var replacement: String? = null

    fun accept(translated: String) {
        if (translated.length > NATIVE_TEXT_LIMIT) rejectNative("La unidad traducida supera el límite de 16 MiB")
        val value = translated.trim()
        if (value.isBlank()) rejectNative("El proveedor ha devuelto una traducción vacía")
        if (value.any { it.code < 32 && it != '\n' && it != '\r' && it != '\t' } ||
            value.any { it == '\ufffe' || it == '\uffff' } ||
            (singleLine && value.any { it == '\r' || it == '\n' || it == '\t' })) rejectNative("La traducción contiene caracteres de control, tabuladores o saltos de línea no compatibles")
        val leading = original.takeWhile { it.isWhitespace() }
        val trailing = original.takeLastWhile { it.isWhitespace() }
        replacement = escape(leading + value + trailing)
    }
}

internal class NativeEdits(val encoding: NativeEncoding, val location: String) {
    val units = mutableListOf<NativeUnit>()
    val fixedEdits = mutableListOf<Triple<Int, Int, String>>()
    fun add(spans: List<NativeSpan>, escape: (String) -> String = { it }, singleLine: Boolean = false) {
        if (spans.any { span -> span.text.any(Char::isLetter) }) units += NativeUnit(spans, escape, singleLine)
    }
    fun bytes(): ByteArray {
        val edits = units.flatMap { unit -> unit.spans.mapIndexed { index, span ->
            Triple(span.start, span.end, if (index == 0) unit.replacement ?: error("Untranslated native unit") else "")
        } }.plus(fixedEdits).sortedWith(compareBy<Triple<Int, Int, String>> { it.first }.thenBy { it.second })
        val output = StringBuilder()
        var cursor = 0
        for ((start, end, value) in edits) {
            check(start >= cursor) { "Overlapping native text edits" }
            if (output.length.toLong() + start - cursor + value.length > NATIVE_TEXT_LIMIT) rejectNative("El texto traducido supera el límite de 16 MiB", location)
            output.append(encoding.text, cursor, start).append(value)
            cursor = end
        }
        if (output.length.toLong() + encoding.text.length - cursor > NATIVE_TEXT_LIMIT) rejectNative("El texto traducido supera el límite de 16 MiB", location)
        output.append(encoding.text, cursor, encoding.text.length)
        return encoding.encode(output.toString())
    }
}
