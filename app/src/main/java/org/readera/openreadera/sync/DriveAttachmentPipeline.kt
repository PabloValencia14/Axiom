package org.readera.openreadera.sync

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.DigestOutputStream
import org.readera.openreadera.core.io.TransferLimits
import org.readera.openreadera.core.io.copyBounded

internal data class DriveAttachmentSource(
    val size: Long,
    val extension: String,
    val mimeType: String,
    val modifiedAt: Long = 0L,
    val open: () -> InputStream
)

internal data class DriveAttachmentRef(
    val sha256: String,
    val remoteName: String,
    val size: Long,
    val extension: String,
    val mimeType: String,
    val modifiedAt: Long = 0L
)

internal interface DriveBlobStore {
    suspend fun contains(name: String): Boolean
    suspend fun upload(name: String, mimeType: String, source: DriveAttachmentSource, expectedSha256: String)
    suspend fun download(name: String, maxBytes: Long): InputStream?
    suspend fun downloadLegacy(name: String, maxBytes: Long): InputStream?
}

internal class DriveAttachmentPipeline(
    private val localRoot: File,
    private val blobStore: DriveBlobStore,
    private val session: DriveAccountGuard.Session? = null
) {
    private fun <T> commit(action: () -> T): T = if (session == null) action() else session.commit(action)

    suspend fun upload(source: DriveAttachmentSource): DriveAttachmentRef {
        val limit = if (source.mimeType.startsWith("image/")) TransferLimits.COVER else TransferLimits.DOCUMENT
        require(source.size in 0..limit) { "Attachment too large" }
        val digest = digest(source.open, source.size)
        session?.checkCurrent()
        val name = "$BLOB_PREFIX$digest"
        if (!blobStore.contains(name)) blobStore.upload(name, source.mimeType, source, digest)
        return DriveAttachmentRef(digest, name, source.size, safeExtension(source.extension), source.mimeType, source.modifiedAt)
    }

    suspend fun restore(reference: DriveAttachmentRef, kind: Kind): File {
        require(reference.sha256.matches(SHA256_PATTERN)) { "Invalid attachment checksum" }
        require(reference.remoteName == "$BLOB_PREFIX${reference.sha256}") { "Invalid attachment name" }
        require(reference.size in 0..kind.limit) { "Attachment too large" }
        val input = blobStore.download(reference.remoteName, reference.size) ?: error("Drive attachment is missing")
        return input.use { install(it, reference.sha256, reference.size, reference.extension, kind) }.also { restored ->
            commit {
                if (reference.modifiedAt > 0L) check(restored.setLastModified(reference.modifiedAt)) {
                    "Unable to preserve attachment modification time"
                }
            }
        }
    }

    suspend fun restoreLegacy(name: String, extension: String, kind: Kind): File? {
        val input = blobStore.downloadLegacy(name, kind.limit) ?: return null
        return input.use { install(it, expectedSha256 = null, expectedSize = null, extension = extension, kind = kind) }
    }

    enum class Kind(val directory: String, val limit: Long) {
        BOOK("imported/synced", TransferLimits.DOCUMENT),
        COVER("covers/synced", TransferLimits.COVER)
    }

    private fun install(
        input: InputStream,
        expectedSha256: String?,
        expectedSize: Long?,
        extension: String,
        kind: Kind
    ): File {
        val directory = File(localRoot, kind.directory)
        check(directory.isDirectory || directory.mkdirs()) { "Unable to prepare local attachment storage" }
        val suffix = safeExtension(extension).takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        val temporary = File(directory, ".attachment.${System.nanoTime()}.tmp")
        try {
            val md = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    copied = copyBounded(source, DigestOutputStream(output, md), kind.limit, expectedSize) {
                        session?.checkCurrent()
                    }
                    output.fd.sync()
                }
            }
            val actualHash = md.digest().toHex()
            check(expectedSha256 == null || actualHash == expectedSha256) { "Drive attachment checksum mismatch" }
            check(expectedSize == null || copied == expectedSize) { "Drive attachment size mismatch" }
            val target = File(directory, "${expectedSha256 ?: actualHash}$suffix")
            return commit {
                if (expectedSha256 != null && target.isFile && target.length() == expectedSize &&
                    digest(open = { FileInputStream(target) }, expectedSize = expectedSize) == expectedSha256
                ) {
                    temporary.delete()
                } else {
                    if (target.exists() && !target.delete()) error("Unable to replace local attachment")
                    check(temporary.renameTo(target)) { "Unable to finalize local attachment" }
                }
                target
            }
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        }
    }

    companion object {
        private const val BLOB_PREFIX = "openreadera_blob_"
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        fun source(file: File, mimeType: String = mimeTypeFor(file.extension)): DriveAttachmentSource {
            require(file.isFile && file.canRead()) { "Attachment is not readable" }
            return DriveAttachmentSource(file.length(), file.extension, mimeType, file.lastModified()) { FileInputStream(file) }
        }

        fun sha256(source: DriveAttachmentSource): String = digest(source.open, source.size)

        fun copyVerified(source: DriveAttachmentSource, output: OutputStream, expectedSha256: String): Long {
            val md = MessageDigest.getInstance("SHA-256")
            val copied = source.open().use { input ->
                copyBounded(input, DigestOutputStream(output, md), TransferLimits.DOCUMENT, source.size)
            }
            check(md.digest().toHex() == expectedSha256) { "Attachment changed while uploading" }
            return copied
        }

        fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
            "pdf" -> "application/pdf"
            "epub" -> "application/epub+zip"
            "mobi" -> "application/x-mobipocket-ebook"
            "fb2" -> "application/x-fb2"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }

        private fun digest(open: () -> InputStream, expectedSize: Long? = null): String {
            val md = MessageDigest.getInstance("SHA-256")
            open().use { input ->
                val discarded = object : OutputStream() {
                    override fun write(value: Int) { md.update(value.toByte()) }
                    override fun write(buffer: ByteArray, offset: Int, length: Int) { md.update(buffer, offset, length) }
                }
                copyBounded(input, discarded, TransferLimits.DOCUMENT, expectedSize)
            }
            return md.digest().toHex()
        }

        private fun safeExtension(extension: String): String =
            extension.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(12)

        private fun ByteArray.toHex(): String = buildString(size * 2) {
            for (byte in this@toHex) append("0123456789abcdef"[(byte.toInt() and 0xff) ushr 4]).append("0123456789abcdef"[byte.toInt() and 0xf])
        }
    }
}
