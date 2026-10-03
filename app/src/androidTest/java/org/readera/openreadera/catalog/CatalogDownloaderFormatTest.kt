package org.readera.openreadera.catalog

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CatalogDownloaderFormatTest {
    @Test fun smallUtf8TextIsAcceptedButHtmlLoginIsNotABook() {
        val downloader = CatalogDownloader(ApplicationProvider.getApplicationContext())
        val file = File.createTempFile("opds-format-", ".part")
        try {
            file.writeText("Hi\n", Charsets.UTF_8)
            assertEquals("txt", downloader.detectFormatExtension(file))
            file.writeText("<!doctype html><html><body>Login</body></html>", Charsets.UTF_8)
            assertNull(downloader.detectFormatExtension(file))
        } finally {
            file.delete()
        }
    }
}
