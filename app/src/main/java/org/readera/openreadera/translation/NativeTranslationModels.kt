package org.readera.openreadera.translation

import org.readera.openreadera.engine.DocumentFormat
import java.io.File
import java.io.IOException

data class ResolvedTranslationSource(
    val file: File,
    val format: DocumentFormat,
    val nativeExtension: String
)

data class TranslationFitPolicy(
    val minFontScale: Float = 0.85f,
    val minLeadingScale: Float = 0.90f,
    val minimumFontPt: Float = 9f
)

typealias NativeTextTranslator = suspend (String, String) -> Result<String>

class TranslationRejectedException(
    message: String,
    val location: String? = null
) : IOException(message)
