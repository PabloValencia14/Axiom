package org.readera.openreadera.engine

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EpubEngineBoundaryTest {
    @Test fun oversizedCompressedChapterAndRepeatedSpineRejectCleanlyThenOrdinaryBookOpens() {
        val root = Files.createTempDirectory("epub-engine-boundary-").toFile()
        val engine = EpubEngine()
        try {
            val oversized = epub(root, "oversized.epub", "x".repeat(2 * 1024 * 1024 + 1))
            assertFalse(engine.open(oversized.path))
            assertTrue(engine.getOutline().isEmpty())
            assertEquals("", engine.getPageText(0))
            val repeated = epub(root, "repeated.epub", "<p>Ordinary text</p>", 2_001)
            assertFalse(engine.open(repeated.path))
            assertTrue(engine.getOutline().isEmpty())
            val ordinary = epub(root, "ordinary.epub", "<h1>A chapter</h1><p>Ordinary prose for an ordinary reader.</p>")
            assertTrue(engine.open(ordinary.path))
            assertEquals(1, engine.getOutline().size)
            assertEquals("A chapter", engine.getOutline().single().title)
        } finally { engine.close(); root.deleteRecursively() }
    }

    private fun epub(root: File, name: String, chapter: String, spineCount: Int = 1): File {
        val file = File(root, name)
        val spine = "<itemref idref='chapter'/>".repeat(spineCount)
        val members = mapOf(
            "META-INF/container.xml" to "<container><rootfile full-path='content.opf'/></container>",
            "content.opf" to "<package><dc:title>Ordinary book</dc:title><manifest><item id='chapter' href='chapter.xhtml'/></manifest><spine>$spine</spine></package>",
            "chapter.xhtml" to chapter
        )
        ZipOutputStream(file.outputStream()).use { output ->
            members.forEach { (entry, content) ->
                output.putNextEntry(ZipEntry(entry)); output.write(content.toByteArray()); output.closeEntry()
            }
        }
        return file
    }
}
