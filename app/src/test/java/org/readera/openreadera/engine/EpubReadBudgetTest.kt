package org.readera.openreadera.engine

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class EpubReadBudgetTest {
    @Test fun compressedMemberIsLimitedEvenWithoutAdvertisedSize() {
        withZip("chapter.xhtml" to "x".repeat(100_000)) { zip ->
            val budget = EpubReadBudget(memberBytes = 16, totalBytes = 32)
            assertTrue(zip.getEntry("chapter.xhtml").compressedSize < 1000)
            assertThrows(IllegalArgumentException::class.java) { budget.readText(zip, zip.getEntry("chapter.xhtml")) }
            assertThrows(IllegalArgumentException::class.java) { budget.readText(zip, ZipEntry("chapter.xhtml")) }
        }
    }

    @Test fun exactMemberAndTotalBudgetAreAcceptedButRepeatedSpineReadsConsumeBudget() {
        withZip("chapter.xhtml" to "café") { zip ->
            val budget = EpubReadBudget(memberBytes = 5, totalBytes = 10)
            assertEquals("café", budget.readText(zip, zip.getEntry("chapter.xhtml")))
            assertEquals("café", budget.readText(zip, zip.getEntry("chapter.xhtml")))
            assertThrows(IllegalArgumentException::class.java) { budget.readText(zip, zip.getEntry("chapter.xhtml")) }
        }
    }

    @Test fun archiveSpineAndRetainedTextCountsAreBounded() {
        withZip("one" to "a", "two" to "b") { zip ->
            EpubReadBudget(entryCount = 2).checkArchive(zip)
            assertThrows(IllegalArgumentException::class.java) { EpubReadBudget(entryCount = 1).checkArchive(zip) }
        }
        val budget = EpubReadBudget(spineCount = 2, totalBytes = 4)
        repeat(2) { budget.addSpineItem() }
        assertThrows(IllegalArgumentException::class.java) { budget.addSpineItem() }
        budget.retainText("book")
        assertThrows(IllegalArgumentException::class.java) { budget.retainText("!") }
        val manifestBudget = EpubReadBudget(entryCount = 2)
        repeat(2) { manifestBudget.addManifestItem() }
        assertThrows(IllegalArgumentException::class.java) { manifestBudget.addManifestItem() }
    }

    private fun withZip(vararg entries: Pair<String, String>, action: (ZipFile) -> Unit) {
        val file = Files.createTempFile("epub-budget-", ".epub").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { output ->
                entries.forEach { (name, content) ->
                    output.putNextEntry(ZipEntry(name)); output.write(content.toByteArray()); output.closeEntry()
                }
            }
            ZipFile(file).use(action)
        } finally { file.delete() }
    }
}
