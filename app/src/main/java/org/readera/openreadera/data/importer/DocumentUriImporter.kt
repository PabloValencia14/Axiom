package org.readera.openreadera.data.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/** Copies a document URI to stable app storage before the reader opens it. */
object DocumentUriImporter {

    suspend fun import(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> uri.path?.let(::File)?.takeIf { it.isFile && it.canRead() }
            "content" -> importContentUri(context, uri)
            else -> null
        }
    }

    private fun importContentUri(context: Context, uri: Uri): File? {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri)
        val rawName = queryDisplayName(resolver, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "document"
        val name = withMimeExtension(sanitizeFileName(rawName), mimeType)
        val directory = File(context.filesDir, "imported").apply { mkdirs() }
        // Keep imports with the same display name separate. A content URI's
        // display name is not a stable identity (Chrome and cloud providers
        // commonly reuse names such as "document.pdf").
        val target = nextAvailableTarget(directory, name)
        val temporary = File(directory, ".${name}.${System.nanoTime()}.tmp")

        return try {
            resolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            if (!temporary.isFile || temporary.length() == 0L) return null
            if (!temporary.renameTo(target)) {
                throw IOException("Cannot finalize imported document: ${target.name}")
            }
            target
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    } catch (_: Exception) {
        null
    }

    private fun sanitizeFileName(rawName: String): String {
        val name = rawName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F\\\\/:*?\"<>|]"), "_")
            .trim()
        return name.ifBlank { "document" }
    }

    private fun withMimeExtension(name: String, mimeType: String?): String {
        if (name.substringAfterLast('.', "").isNotBlank()) return name
        val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return if (extension.isNullOrBlank()) name else "$name.$extension"
    }

    private fun nextAvailableTarget(directory: File, requestedName: String): File {
        val direct = File(directory, requestedName)
        if (!direct.exists()) return direct

        val dot = requestedName.lastIndexOf('.')
        val stem = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        var suffix = 1
        var candidate: File
        do {
            candidate = File(directory, "$stem ($suffix)$extension")
            suffix++
        } while (candidate.exists())
        return candidate
    }
}
