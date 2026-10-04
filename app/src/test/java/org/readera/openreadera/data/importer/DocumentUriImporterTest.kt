package org.readera.openreadera.data.importer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DocumentUriImporterTest {
    @Test fun externalFileUrisRejectAppPrivateFilesAndCanonicalTraversal() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val secret = File(context.dataDir, "boundary-secret.txt").apply { writeText("not a document") }
        val root = Files.createTempDirectory("external-uri-").toFile()
        try {
            assertNull(DocumentUriImporter.import(context, Uri.fromFile(secret)))
            assertNull(DocumentUriImporter.import(context, Uri.fromFile(File(context.filesDir, "../${secret.name}"))))
            val ordinary = File(root, "ordinary.pdf").apply { writeText("%PDF ordinary") }
            assertEquals(ordinary.canonicalFile, DocumentUriImporter.import(context, Uri.fromFile(ordinary)))
        } finally { secret.delete(); root.deleteRecursively() }
    }

    @Test fun externalFileUriSymlinkCannotReachAppPrivateStorage() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val secret = File(context.dataDir, "boundary-symlink-secret.txt").apply { writeText("not a document") }
        val root = Files.createTempDirectory("external-uri-symlink-").toFile()
        try {
            val alias = File(root, "secret.pdf")
            createSymlinkOrSkip(alias, secret)
            assertNull(DocumentUriImporter.import(context, Uri.fromFile(alias)))
        } finally { secret.delete(); root.deleteRecursively() }
    }

    @Test fun advertisedOversizeNeverOpensProviderAndActualOversizeCleansPartial() {
        val context = RuntimeEnvironment.getApplication()
        val fixture = Files.createTempFile("provider-", ".pdf").toFile()
        val uri = Uri.parse("content://boundary.documents/document")
        val provider = TestProvider(fixture)
        provider.attachInfo(context, ProviderInfo().apply { authority = uri.authority })
        ShadowContentResolver.registerProviderInternal(uri.authority!!, provider)
        val directory = File(context.filesDir, "imported")
        val originalNames = directory.list()?.toSet().orEmpty()
        try {
            fixture.writeText("books")
            provider.advertised = 5
            assertThrows(IllegalArgumentException::class.java) { DocumentUriImporter.importContentUri(context, uri, 4) }
            assertEquals(0, provider.opens)
            provider.advertised = -1
            assertThrows(IllegalArgumentException::class.java) { DocumentUriImporter.importContentUri(context, uri, 4) }
            assertEquals(1, provider.opens)
            assertEquals(originalNames, directory.list()?.toSet().orEmpty())
            provider.advertised = 1 // A dishonest provider cannot bypass the actual byte budget.
            assertThrows(IllegalArgumentException::class.java) { DocumentUriImporter.importContentUri(context, uri, 4) }
            assertEquals(originalNames, directory.list()?.toSet().orEmpty())
            fixture.writeText("book")
            provider.advertised = 4
            val imported = DocumentUriImporter.importContentUri(context, uri, 4)!!
            try { assertEquals("book", imported.readText()) } finally { imported.delete() }
        } finally { fixture.delete() }
    }

    private class TestProvider(private val file: File) : ContentProvider() {
        var advertised = -1L
        var opens = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/pdf"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).apply {
                addRow(columns.map { if (it == OpenableColumns.SIZE) advertised else "provider-book.pdf" })
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens++
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
