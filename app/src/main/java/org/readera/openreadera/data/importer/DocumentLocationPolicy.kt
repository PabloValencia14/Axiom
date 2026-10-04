package org.readera.openreadera.data.importer

import android.content.Context
import java.io.File

/** Canonical paths, not suffixes, distinguish documents from private application state. */
internal class DocumentLocationPolicy(
    private val privateRoots: List<File>,
    private val documentDirectories: List<File>,
    private val coverDirectories: List<File>
) {
    fun externalDocument(file: File): File? = checked(file) { canonical ->
        privateRoots.none { canonical.isInside(it.resolvedFile()) }
    }

    fun uploadDocument(file: File): File? = checked(file) { canonical ->
        val insidePrivate = privateRoots.any { canonical.isInside(it.resolvedFile()) }
        !insidePrivate || documentDirectories.any { directory ->
            val root = directory.resolvedFile()
            // A symlink replacing the approved directory itself must not approve secrets.
            root == directory.absoluteFile && canonical.isInside(root)
        }
    }

    fun uploadCover(file: File): File? = checked(file) { canonical ->
        coverDirectories.any { directory ->
            val root = directory.resolvedFile()
            root == directory.absoluteFile && canonical.isInside(root)
        }
    }

    private fun checked(file: File, allowed: (File) -> Boolean): File? = try {
        file.resolvedFile().takeIf { it.isFile && it.canRead() && allowed(it) }
    } catch (_: Exception) {
        null
    }

    // Windows canonicalFile does not resolve symbolic links. Android's canonicalFile
    // does, and keeps this boundary compatible with API 24 (NIO paths need API 26).
    private fun File.resolvedFile(): File =
        if (File.separatorChar == '\\' && exists()) toPath().toRealPath().toFile() else canonicalFile

    private fun File.isInside(directory: File): Boolean {
        var ancestor: File? = this
        while (ancestor != null) {
            if (ancestor == directory) return true
            ancestor = ancestor.parentFile
        }
        return false
    }

    companion object {
        fun forContext(context: Context): DocumentLocationPolicy {
            val contexts = listOf(context, context.createDeviceProtectedStorageContext())
            val roots = contexts.map { it.dataDir.canonicalFile } +
                listOfNotNull(context.getExternalFilesDir(null)?.parentFile, context.externalCacheDir?.parentFile)
            val files = context.filesDir.canonicalFile
            return DocumentLocationPolicy(
                roots,
                listOf("imported", "scans", "pdf-tools").map { File(files, it) },
                listOf(File(files, "covers"))
            )
        }
    }
}
