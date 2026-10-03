package org.readera.openreadera.sync

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.readera.openreadera.data.model.Book
import org.junit.Assert.assertEquals
import org.readera.openreadera.data.model.DrawingStroke
import org.readera.openreadera.data.model.StrokePoint
import org.readera.openreadera.data.model.StrokeToolType

@RunWith(RobolectricTestRunner::class)

class DriveSyncSnapshotTest {

    @Test
    fun remoteOnlyBookRehydratesToChecksumVerifiedLocalFile() = runBlocking {
        val payload = "remote PDF bytes".toByteArray()
        val sha256 = sha256(payload)
        val remoteName = "openreadera_blob_$sha256"
        val blobStore = MemoryBlobStore(mapOf(remoteName to payload))
        val rootDirectory = Files.createTempDirectory("drive-sync-").toFile()
        try {
            val pipeline = DriveAttachmentPipeline(rootDirectory, blobStore)
            val remoteState = JSONObject().apply {
                put("version", DriveSyncSnapshotCodec.CURRENT_VERSION)
                put("books", JSONArray().put(JSONObject().apply {
                    put("id", 42L)
                    put("title", "Libro remoto")
                    put("author", "Autora")
                    put("format", "PDF")
                    put("fileSize", payload.size)
                    put("sha1", "legacy-book-sha1")
                    put("bookAttachment", JSONObject().apply {
                        put("sha256", sha256)
                        put("remoteName", remoteName)
                        put("size", payload.size)
                        put("extension", "pdf")
                        put("mimeType", "application/pdf")
                        put("modifiedAt", 1L)
                    })
                }))
            }

            val hydrated = rehydrateRemoteAttachments(remoteState, emptyList(), pipeline)
            val restoredPath = hydrated.getJSONArray("books")
                .getJSONObject(0)
                .getString("filePath")
            val restored = File(restoredPath)

            assertTrue(restored.isFile)
            assertArrayEquals(payload, restored.readBytes())
            assertTrue(sha256(restored.readBytes()) == sha256)
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun portableSnapshotIncludesSafeSettingsButNoCredentialsOrDevicePaths() {
        val readerPreferences = DriveSyncSafeState.readerPreferencesFromValues(
            mapOf(
                "font_size" to 18,
                "volume_keys" to false,
                "google_token" to "ya29.secret-token",
                "remix_userkey" to "zlib-secret",
                "saf_folder_uri" to "content://device-specific"
            )
        )
        val readingStats = DriveSyncSafeState.readingStatsFromValues(
            mapOf(
                "total_seconds" to 120L,
                "day_sec_2026-09-28" to 120L,
                "ciphertext" to "encrypted-secret"
            )
        )
        val syncPreferences = DriveSyncSafeState.syncPreferencesFromValues(
            mapOf(
                "auto_sync_enabled" to true,
                "target_folder_id" to "device-specific-folder"
            )
        )
        val timedInk = DrawingStroke(bookId = 7L, page = 2, pointsJson =
            DrawingStroke.serializeTimedPoints(listOf(StrokePoint(0.2f, 0.3f, 0.4f, 0L),
                StrokePoint(0.7f, 0.8f, 0.9f, 53L))))
        val snapshot = DriveSyncSnapshotCodec.encode(
            DriveSnapshotContents(
                books = listOf(
                    Book(
                        id = 7L,
                        title = "Libro local",
                        filePath = "/device/library/book.pdf",
                        format = "PDF",
                        coverPath = "/device/covers/book.jpg"
                    )
                ),
                bookmarks = emptyList(),
                quotes = emptyList(),
                drawingStrokes = StrokeToolType.entries.map { timedInk.copy(toolType = it) },
                collections = emptyList(),
                bookCollections = emptyList(),
                searchQueries = emptyList(),
                attachmentsByBookId = emptyMap<Long, BookAttachmentRefs>(),
                drawingTombstones = emptySet(),
                readerPreferences = readerPreferences,
                readingStats = readingStats,
                syncPreferences = syncPreferences,
                opdsCatalogs = JSONArray(),
                zLibraryPreferences = JSONObject().put("customMirror", "https://singlelogin.rs"),
                manualCategoryOverrides = JSONArray(),
                timestamp = 1L
            )
        )

        assertFalse(readerPreferences.getBoolean("volume_keys"))
        assertTrue(snapshot.contains("font_size"))
        assertTrue(snapshot.contains("day_sec_2026-09-28"))
        assertFalse(snapshot.contains("google_token"))
        assertFalse(snapshot.contains("ya29.secret-token"))
        assertFalse(snapshot.contains("remix_userkey"))
        assertFalse(snapshot.contains("zlib-secret"))
        assertFalse(snapshot.contains("saf_folder_uri"))
        assertFalse(snapshot.contains("target_folder_id"))
        assertFalse(snapshot.contains("ciphertext"))
        assertFalse(snapshot.contains("encrypted-secret"))
        assertFalse(snapshot.contains("/device/library/book.pdf"))
        assertFalse(snapshot.contains("/device/covers/book.jpg"))
        val encodedStrokes = JSONObject(snapshot).getJSONArray("drawingStrokes")
        for ((index, tool) in StrokeToolType.entries.withIndex()) {
            val encodedInk = encodedStrokes.getJSONObject(index)
            assertEquals(tool.name, encodedInk.getString("toolType"))
            assertEquals(timedInk.pointsJson, encodedInk.getString("pointsJson"))
            assertEquals(timedInk.parseData(), timedInk.copy(pointsJson = encodedInk.getString("pointsJson")).parseData())
        }
    }

    @Test
    fun everyDrawingPreferenceAndStoredBrushSurvivesPortableEncoding() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        for (tool in StrokeToolType.entries.map { it.name } + "ERASER") {
            val safe = DriveSyncSafeState.readerPreferencesFromValues(mapOf("drawing_tool" to tool))
            assertEquals(tool, safe.getString("drawing_tool"))
            DriveSyncSafeState.applyReaderPreferences(context, safe)
            assertEquals(tool, DriveSyncSafeState.readerPreferences(context).getString("drawing_tool"))
            DriveSyncSafeState.applyReaderPreferences(context, JSONObject().put("drawing_tool", "UNKNOWN"))
            assertEquals(tool, DriveSyncSafeState.readerPreferences(context).getString("drawing_tool"))
        }
    }

    private class MemoryBlobStore(initial: Map<String, ByteArray>) : DriveBlobStore {
        private val blobs = initial.toMutableMap()

        override suspend fun contains(name: String): Boolean = name in blobs

        override suspend fun upload(
            name: String,
            mimeType: String,
            source: DriveAttachmentSource,
            expectedSha256: String
        ) {
            val output = ByteArrayOutputStream()
            DriveAttachmentPipeline.copyVerified(source, output, expectedSha256)
            blobs[name] = output.toByteArray()
        }

        override suspend fun download(name: String) = blobs[name]?.inputStream()

        override suspend fun downloadLegacy(name: String) = blobs[name]?.inputStream()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
