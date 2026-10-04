package org.readera.openreadera.catalog

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.readera.openreadera.core.io.TransferLimits
import org.readera.openreadera.data.scanner.StorageScanner

@RunWith(RobolectricTestRunner::class)
class CatalogTransferBoundaryTest {
    @Test fun documentAdvertisedOversizeDoesNotReadBodyOrLeaveTemporaryFiles() = runBlocking {
        var reads = 0
        val body = object : ResponseBody() {
            override fun contentType() = "application/pdf".toMediaType()
            override fun contentLength() = TransferLimits.DOCUMENT + 1
            override fun source(): okio.BufferedSource { reads++; error("Oversized body must not be read") }
            override fun close() {}
        }
        val downloader = downloader(body)
        val directory = StorageScanner.getAppBooksDirectory().apply { mkdirs() }
        val before = directory.list()?.toSet().orEmpty()
        val result = downloader.download(CatalogBook(id = "oversized", title = "Oversized", extension = "PDF", downloadUrl = "https://catalog.example/book.pdf")) {}
        assertTrue(result.isFailure)
        assertEquals(0, reads)
        assertEquals(before, directory.list()?.toSet().orEmpty())
    }

    @Test fun coverAdvertisedAndActualBoundsPreserveExistingCoverAndCleanTemporaryOutput() {
        val root = Files.createTempDirectory("catalog-cover-boundary-").toFile()
        try {
            val target = File(root, "cover.jpg").apply { writeText("original") }
            val oversizedAdvertised = body("", 5)
            assertThrows(IllegalArgumentException::class.java) { downloader(oversizedAdvertised).downloadCover("https://catalog.example/cover.jpg", target, 4) }
            assertEquals("original", target.readText())
            assertEquals(listOf("cover.jpg"), root.list()!!.toList())
            assertThrows(IllegalArgumentException::class.java) { downloader(body("books", -1)).downloadCover("https://catalog.example/cover.jpg", target, 4) }
            assertEquals("original", target.readText())
            assertEquals(listOf("cover.jpg"), root.list()!!.toList())
            target.delete()
            assertTrue(downloader(body("book", 4)).downloadCover("https://catalog.example/cover.jpg", target, 4))
            assertEquals("book", target.readText())
        } finally { root.deleteRecursively() }
    }

    private fun body(text: String, advertised: Long) = object : ResponseBody() {
        private val buffer = Buffer().writeUtf8(text)
        override fun contentType() = "image/jpeg".toMediaType()
        override fun contentLength() = advertised
        override fun source(): okio.BufferedSource = buffer
    }

    private fun downloader(body: ResponseBody): CatalogDownloader {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        return CatalogDownloader(RuntimeEnvironment.getApplication(), client)
    }
}
