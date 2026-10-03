package org.readera.openreadera.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CatalogDownloaderCollisionTest {
    @Test fun sameTitleReservationNeverReplacesExistingBook() {
        val dir = createTempDir(prefix = "catalog-collision-")
        try {
            val existing = File(dir, "Novel - Writer.epub")
            existing.writeText("pre-existing book")
            val reserved = reserveCatalogDestination(dir, "Novel", "Writer", "epub")
            assertNotEquals(existing.canonicalFile, reserved.canonicalFile)
            assertEquals("pre-existing book", existing.readText())
            assertEquals("Novel - Writer (1).epub", reserved.name)
        } finally {
            dir.deleteRecursively()
        }
    }
}
