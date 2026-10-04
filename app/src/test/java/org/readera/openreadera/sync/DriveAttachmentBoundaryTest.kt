package org.readera.openreadera.sync

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.readera.openreadera.core.io.TransferLimits
import org.readera.openreadera.data.db.AppDatabase
import org.readera.openreadera.data.importer.DocumentLocationPolicy
import org.readera.openreadera.data.model.Book

@RunWith(RobolectricTestRunner::class)
class DriveAttachmentBoundaryTest {
    @Test fun changingIdentityDuringDownloadPreventsLocalAttachmentPublication() {
        val root = Files.createTempDirectory("drive-attachment-identity-").toFile()
        try {
            val session = DriveAccountGuard.capture()
            val store = TestStore("book".toByteArray()).apply { onRead = { DriveAccountGuard.invalidate() } }
            val pipeline = DriveAttachmentPipeline(root, store, session)
            val hash = hash("book".toByteArray())
            val reference = DriveAttachmentRef(hash, "openreadera_blob_$hash", 4, "pdf", "application/pdf")
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                runBlocking { pipeline.restore(reference, DriveAttachmentPipeline.Kind.BOOK) }
            }
            assertTrue(File(root, "imported/synced").listFiles().orEmpty().isEmpty())
            assertTrue(store.closed)
        } finally { root.deleteRecursively() }
    }

    @Test fun expectedSizeAndChecksumFailuresLeaveNoPartialFileAndValidAttachmentRestores() = runBlocking {
        val root = Files.createTempDirectory("drive-attachment-boundary-").toFile()
        try {
            val store = TestStore("books".toByteArray())
            val pipeline = DriveAttachmentPipeline(root, store)
            val hash = hash("book".toByteArray())
            val reference = DriveAttachmentRef(hash, "openreadera_blob_$hash", 4, "pdf", "application/pdf")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { pipeline.restore(reference, DriveAttachmentPipeline.Kind.BOOK) } }
            assertEquals(4L, store.requestedLimit)
            assertTrue(File(root, "imported/synced").listFiles().orEmpty().isEmpty())
            assertTrue(store.closed)
            store.payload = "boo".toByteArray()
            assertThrows(IllegalArgumentException::class.java) { runBlocking { pipeline.restore(reference, DriveAttachmentPipeline.Kind.BOOK) } }
            store.payload = "fake".toByteArray()
            assertThrows(IllegalStateException::class.java) { runBlocking { pipeline.restore(reference, DriveAttachmentPipeline.Kind.BOOK) } }
            assertTrue(File(root, "imported/synced").listFiles().orEmpty().isEmpty())
            store.payload = "book".toByteArray()
            assertEquals("book", pipeline.restore(reference, DriveAttachmentPipeline.Kind.BOOK).readText())
            val priorDownloads = store.downloads
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { pipeline.restore(reference.copy(size = TransferLimits.COVER + 1), DriveAttachmentPipeline.Kind.COVER) }
            }
            assertEquals(priorDownloads, store.downloads)
        } finally { root.deleteRecursively() }
    }

    @Test fun uploadHashAndCopyRejectSizeChangesBeforeBlobPublication() = runBlocking {
        val root = Files.createTempDirectory("drive-upload-boundary-").toFile()
        try {
            val store = TestStore(byteArrayOf())
            val pipeline = DriveAttachmentPipeline(root, store)
            val invalid = DriveAttachmentSource(4, "pdf", "application/pdf") { "books".byteInputStream() }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { pipeline.upload(invalid) } }
            assertEquals(0, store.uploads)
            val output = ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) {
                DriveAttachmentPipeline.copyVerified(invalid, output, hash("book".toByteArray()))
            }
            assertTrue(output.size() <= 4)
            val valid = DriveAttachmentSource(4, "pdf", "application/pdf") { "book".byteInputStream() }
            assertEquals(4L, pipeline.upload(valid).size)
            assertEquals(1, store.uploads)
        } finally { root.deleteRecursively() }
    }

    @Test fun existingPrivateLibraryRecordsAreNotUploadedButImportedAndGeneratedDocumentsAre() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("drive-private-boundary-").toFile().canonicalFile
        try {
            val privateRoot = File(root, "app").apply { mkdirs() }
            val approved = listOf("imported", "scans", "pdf-tools").map { File(privateRoot, it).apply { mkdirs() } }
            val policy = DocumentLocationPolicy(listOf(privateRoot), approved, emptyList())
            val secret = File(privateRoot, "google_sync_prefs.xml").apply { writeText("token") }
            val store = TestStore(byteArrayOf())
            val pipeline = DriveAttachmentPipeline(root, store)
            val transfer = DriveSyncStateTransfer(AppDatabase.getInstance(context), pipeline, policy)
            val books = listOf(Book(id = 1, title = "Secret disguised as a book", filePath = secret.path, format = "PDF", coverPath = secret.path)) +
                approved.mapIndexed { index, directory ->
                    val file = File(directory, "valid.pdf").apply { writeText("valid-$index") }
                    Book(id = index + 2L, title = "Valid", filePath = file.path, format = "PDF")
                }
            val refs = transfer.uploadLocalAttachments(books, null)
            assertNull(refs.getValue(1).book)
            assertNull(refs.getValue(1).cover)
            assertEquals(3, store.uploads)
            books.drop(1).forEach { assertNotNull(refs.getValue(it.id).book) }
        } finally { root.deleteRecursively() }
    }

    private class TestStore(var payload: ByteArray) : DriveBlobStore {
        var uploads = 0
        var downloads = 0
        var requestedLimit = -1L
        var closed = false
        var onRead: (() -> Unit)? = null
        override suspend fun contains(name: String) = false
        override suspend fun upload(name: String, mimeType: String, source: DriveAttachmentSource, expectedSha256: String) {
            DriveAttachmentPipeline.copyVerified(source, ByteArrayOutputStream(), expectedSha256)
            uploads++
        }
        override suspend fun download(name: String, maxBytes: Long): InputStream {
            downloads++; requestedLimit = maxBytes; closed = false
            return object : java.io.ByteArrayInputStream(payload) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(buffer, offset, length)
                    onRead?.also { onRead = null }?.invoke()
                    return count
                }
                override fun close() { closed = true; super.close() }
            }
        }
        override suspend fun downloadLegacy(name: String, maxBytes: Long) = download(name, maxBytes)
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
