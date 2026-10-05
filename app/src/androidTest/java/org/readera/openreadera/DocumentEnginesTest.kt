package org.readera.openreadera

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readera.openreadera.engine.DocxEngine
import org.readera.openreadera.engine.EngineManager
import org.readera.openreadera.engine.Fb2Engine
import org.readera.openreadera.engine.MobiEpubEngine
import org.readera.openreadera.engine.DocumentEngine
import org.readera.openreadera.engine.EpubEngine
import org.readera.openreadera.engine.TxtEngine
import org.readera.openreadera.engine.DocumentFormat
import org.readera.openreadera.translation.DocumentTranslationCoordinator
import java.security.MessageDigest
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class DocumentEnginesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun docxExtractsOrderedParagraphsTabsAndCellsAndCleansTemporaryText() {
        val file = File.createTempFile("docx-test-", ".docx", context.cacheDir)
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:r><w:t>Hola &amp;</w:t></w:r><w:r><w:tab/><w:t>Mundo</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Primera</w:t></w:r></w:p></w:tc>
            <w:tc><w:p><w:r><w:t>Segunda</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            </w:body></w:document>""".trimIndent()
        zipDocument(file, "word/document.xml", xml)
        val engine = DocxEngine(context)
        val before = temporaryFiles("docx-")
        try {
            assertTrue(engine.open(file.absolutePath))
            assertTrue(engine.getPageCount() > 0)
            val text = engine.getPageText(0)
            assertTrue(text.contains("Hola &\tMundo"))
            assertTrue(text.contains("Primera\tSegunda"))
            assertEquals(text, engine.getPageTextLayout(0).text)
            assertTrue(engine.getPageTextLayout(0).words.isNotEmpty())
            val created = temporaryFiles("docx-") - before
            assertEquals(1, created.size)
            engine.close()
            assertFalse(created.single().exists())
        } finally {
            engine.close()
            file.delete()
        }

        val corrupt = File.createTempFile("corrupt-word-", ".docx", context.cacheDir)
        corrupt.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00))
        try {
            assertFalse(engine.open(corrupt.absolutePath))
            assertTrue(temporaryFiles("docx-").all { it in before })
        } finally {
            engine.close()
            corrupt.delete()
        }
    }

    @Test
    fun fb2ExtractsProseAndRejectsWrongRootAndDeclarations() {
        val engine = Fb2Engine(context)
        val valid = File.createTempFile("fb2-test-", ".fb2", context.cacheDir)
        val before = temporaryFiles("fb2-")
        valid.writeText(
            """<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><body><section><title><p>Capítulo uno</p></title><p>Texto &amp; Unicode ñ.</p></section></body></FictionBook>""",
            Charsets.UTF_8
        )
        try {
            assertTrue(engine.open(valid.absolutePath))
            val text = engine.getPageText(0)
            assertTrue(text.contains("Capítulo uno"))
            assertTrue(text.contains("Texto & Unicode ñ."))
            assertEquals(text, engine.getPageTextLayout(0).text)
            assertTrue(engine.getPageTextLayout(0).words.isNotEmpty())
            val created = temporaryFiles("fb2-") - before
            assertEquals(1, created.size)
            engine.close()
            assertFalse(created.single().exists())
            assertFalse(text.contains("<FictionBook"))

            valid.writeText("<document><body><p>No es FictionBook</p></body></document>", Charsets.UTF_8)
            assertFalse(engine.open(valid.absolutePath))
            assertTrue(temporaryFiles("fb2-").all { it in before })
            valid.writeText(
                """<!DOCTYPE FictionBook [<!ENTITY x SYSTEM "file:///etc/passwd">]><FictionBook><body><p>&x;</p></body></FictionBook>""",
                Charsets.UTF_8
            )
            assertFalse(engine.open(valid.absolutePath))
            assertTrue(temporaryFiles("fb2-").all { it in before })
        } finally {
            engine.close()
            valid.delete()
        }
    }

    @Test
    fun zipWithoutEbookStructureAndRtfNeverUseFakeRenderers() {
        val plainZip = File.createTempFile("unknown-", ".epub", context.cacheDir)
        zipDocument(plainZip, "readme.txt", "not an ebook")
        val rtf = File.createTempFile("unsupported-", ".rtf", context.cacheDir)
        val fb2Zip = File.createTempFile("archive-", ".fb2.zip", context.cacheDir)
        zipDocument(fb2Zip, "book.fb2", "<FictionBook><body><p>Not accepted from ZIP</p></body></FictionBook>")
        rtf.writeText("{\\rtf1\\ansi This is RTF}", Charsets.UTF_8)
        try {
            val zipEngine = EngineManager(context).getEngineForDocument(plainZip.absolutePath)
            assertEquals("Formato no compatible: Documento", zipEngine.engineName)
            assertFalse(zipEngine.open(plainZip.absolutePath))
            assertEquals(0, zipEngine.getPageCount())
            zipEngine.close()
            val archiveEngine = EngineManager(context).getEngineForDocument(fb2Zip.absolutePath)
            assertEquals("Formato no compatible: Documento", archiveEngine.engineName)
            assertFalse(archiveEngine.open(fb2Zip.absolutePath))
            archiveEngine.close()

            val rtfEngine = EngineManager(context).getEngineForDocument(rtf.absolutePath)
            assertEquals("Formato no compatible: RTF", rtfEngine.engineName)
            assertFalse(rtfEngine.open(rtf.absolutePath))
            assertEquals(0, rtfEngine.getPageCount())
            rtfEngine.close()
        } finally {
            plainZip.delete()
            rtf.delete()
            fb2Zip.delete()
        }
    }

    @Test
    fun mobiOpensRealPagesReopensCleansCacheAndRejectsEncryptedCopy() {
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val fixtureBytes = assetContext.assets.open("axiom-self-authored.mobi").use { it.readBytes() }
        val source = File.createTempFile("mobi-test-", ".mobi", context.cacheDir)
        source.writeBytes(fixtureBytes)
        val engine = EngineManager(context).getEngineForDocument(source.absolutePath)
        assertTrue(engine is MobiEpubEngine)
        val before = temporaryFiles("mobi-converted-")
        try {
            assertTrue(engine.open(source.absolutePath))
            assertTrue(engine.getPageCount() > 0)
            val text = engine.getPageText(0)
            assertTrue(text.isNotBlank())
            assertEquals(text, engine.getPageTextLayout(0).text)
            assertTrue(engine.getPageTextLayout(0).words.isNotEmpty())
            assertFalse(text.contains("Contenido de lectura de la página"))
            assertTrue(engine.search(text.trim().split(Regex("\\s+")).first()).isNotEmpty())
            val generated = temporaryFiles("mobi-converted-") - before
            assertEquals(1, generated.size)
            engine.close()
            assertFalse(generated.single().exists())

            assertTrue(engine.open(source.absolutePath))
            assertTrue(engine.getPageCount() > 0)
            engine.close()
            assertTrue((temporaryFiles("mobi-converted-") - before).isEmpty())
        } finally {
            engine.close()
            source.delete()
        }

        val encrypted = File.createTempFile("mobi-encrypted-", ".mobi", context.cacheDir)
        encrypted.writeBytes(fixtureBytes)
        RandomAccessFile(encrypted, "rw").use { file ->
            file.seek(78)
            val recordOffset = file.readInt().toLong() and 0xffff_ffffL
            assertTrue(recordOffset + 14 < file.length())
            file.seek(recordOffset + 12)
            assertEquals(0, file.readUnsignedShort())
            file.seek(recordOffset + 12)
            file.write(0)
            file.write(2)
            file.seek(recordOffset + 12)
            assertEquals(2, file.readUnsignedShort())
        }
        val drmEngine = EngineManager(context).getEngineForDocument(encrypted.absolutePath)
        try {
            assertFalse(drmEngine.open(encrypted.absolutePath))
            assertTrue((temporaryFiles("mobi-converted-") - before).isEmpty())
            val originalAssetBytes = assetContext.assets.open("axiom-self-authored.mobi").use { it.readBytes() }
            assertArrayEquals(fixtureBytes, originalAssetBytes)
        } finally {
            drmEngine.close()
            encrypted.delete()
        }
    }

    @Test
    fun translatedDocxEpubFb2AndTextReopenInTheirNativeEnginesWithoutChangingSources() = runBlocking {
        val root = File(context.cacheDir, "native-package-engine-${System.nanoTime()}").apply { mkdirs() }
        val outputDirectory = File(root, "output").apply { mkdirs() }
        val wordNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val xhtmlNs = "http://www.w3.org/1999/xhtml"
        val fb2Ns = "http://www.gribuser.ru/xml/fictionbook/2.0"
        val fixtures = linkedMapOf(
            DocumentFormat.DOCX to linkedMapOf(
                "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""".toByteArray(),
                "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="document" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""".toByteArray(),
                "word/document.xml" to """<w:document xmlns:w="$wordNs"><w:body><w:p><w:r><w:t>DOCX native phrase</w:t></w:r></w:p><w:sectPr><w:pgSz w:w="12240" w:h="15840"/></w:sectPr></w:body></w:document>""".toByteArray()
            ),
            DocumentFormat.EPUB to linkedMapOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
                "OPS/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier>self-authored</dc:identifier><dc:title>Fixture</dc:title><dc:language>en</dc:language></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="style" href="style.css" media-type="text/css"/><item id="image" href="image.png" media-type="image/png"/></manifest><spine><itemref idref="chapter"/></spine></package>""".toByteArray(),
                "OPS/chapter.xhtml" to """<html xmlns="$xhtmlNs"><head><title>Fixture</title><link rel="stylesheet" href="style.css"/></head><body><p>EPUB native phrase</p><img src="image.png" alt="fixture"/></body></html>""".toByteArray(),
                "OPS/style.css" to "p { color: #112233; }".toByteArray(),
                "OPS/image.png" to android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=", android.util.Base64.DEFAULT)
            )
        )
        val sources = linkedMapOf<DocumentFormat, File>()
        try {
            for ((format, members) in fixtures) {
                val suffix = format.extension
                val source = File(root, "fixture.$suffix")
                zipTranslationFixture(source, members, storedMimetype = format == DocumentFormat.EPUB)
                sources[format] = source
            }
            val fb2 = File(root, "fixture.fb2").apply {
                writeText("""<FictionBook xmlns="$fb2Ns"><body><section><p>FB2 native phrase</p></section></body></FictionBook>""")
            }
            sources[DocumentFormat.FB2] = fb2
            val text = File(root, "fixture.txt").apply { writeText("TXT native phrase\r\n") }
            sources[DocumentFormat.TXT] = text
            val hashes = sources.mapValues { (_, source) -> MessageDigest.getInstance("SHA-256").digest(source.readBytes()) }
            val coordinator = DocumentTranslationCoordinator(context)
            val expected = mapOf(
                DocumentFormat.DOCX to "Traducido DOCX native phrase",
                DocumentFormat.EPUB to "Traducido EPUB native phrase",
                DocumentFormat.FB2 to "Traducido FB2 native phrase",
                DocumentFormat.TXT to "Traducido TXT native phrase"
            )
            for ((format, source) in sources) {
                val result = coordinator.translateCopy(
                    source = coordinator.resolveSource(source.path),
                    outputDirectory = outputDirectory,
                    title = "Package fixture",
                    targetLanguage = "es",
                    translate = { value, language ->
                        assertEquals("es", language)
                        Result.success("Traducido $value")
                    },
                    onProgress = { _, _ -> }
                )
                assertEquals(format.extension, result.extension)
                assertArrayEquals(hashes.getValue(format), MessageDigest.getInstance("SHA-256").digest(source.readBytes()))
                val engine: DocumentEngine = when (format) {
                    DocumentFormat.DOCX -> DocxEngine(context)
                    DocumentFormat.EPUB -> EpubEngine()
                    DocumentFormat.FB2 -> Fb2Engine(context)
                    DocumentFormat.TXT -> TxtEngine()
                    else -> error("Unexpected fixture")
                }
                try {
                    assertTrue("$format native output opens", engine.open(result.path))
                    assertTrue("$format has a rendered/text page", engine.getPageCount() > 0)
                    val readable = (0 until engine.getPageCount()).joinToString("\n") { engine.getPageText(it) }
                    assertTrue("$format keeps translated text: $readable", readable.contains(expected.getValue(format)))
                } finally {
                    engine.close()
                }
                if (format == DocumentFormat.EPUB) {
                    val original = fixtures.getValue(format)
                    ZipFile(result).use { zip ->
                        for (name in listOf("mimetype", "OPS/style.css", "OPS/image.png"))
                            assertArrayEquals(name, original.getValue(name), zip.getInputStream(zip.getEntry(name)).use { it.readBytes() })
                        assertEquals("mimetype", zip.entries().nextElement().name)
                    }
                }
            }
            assertEquals(4, outputDirectory.listFiles()!!.size)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun zipTranslationFixture(file: File, members: Map<String, ByteArray>, storedMimetype: Boolean) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            val entries = if (storedMimetype) members.entries.filter { it.key == "mimetype" } + members.entries.filter { it.key != "mimetype" } else members.entries
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                if (name == "mimetype" && storedMimetype) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
    private fun temporaryFiles(prefix: String): Set<File> =
        context.cacheDir.listFiles()?.filter { it.name.startsWith(prefix) }?.toSet().orEmpty()

    private fun zipDocument(file: File, entryName: String, contents: String) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(contents.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
