package org.readera.openreadera.translation

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readera.openreadera.engine.DocumentFormat
import org.readera.openreadera.engine.EngineManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DocumentTranslationCoordinatorTest {
    private lateinit var root: File
    private lateinit var output: File
    private lateinit var cache: File
    private lateinit var manager: EngineManager

    @Before fun setUp() {
        root = Files.createTempDirectory("native-translation-flow-").toFile()
        output = File(root, "output").apply { mkdirs() }
        cache = File(root, "cache").apply { mkdirs() }
        manager = EngineManager(RuntimeEnvironment.getApplication())
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun coordinator(
        convert: (File, File) -> Boolean = { _, _ -> error("Unexpected Kindle conversion") },
        adapter: suspend (ResolvedTranslationSource, File, String, NativeTextTranslator, (Int, Int) -> Unit) -> Unit
    ) = DocumentTranslationCoordinator(cache, manager::resolveDocumentFormat, adapter, convert)

    @Test fun actualTextAdapterCompletes205UnitsWithoutPageExtractionOrNetwork() = runTest {
        val flow = DocumentTranslationCoordinator(RuntimeEnvironment.getApplication())
        val file = File(root, "paragraphs.md").apply {
            writeText("\uFEFF" + (0 until 205).joinToString("\n\n") { "Native paragraph" } + "\n")
        }
        val visited = mutableListOf<String>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = flow.translateCopy(flow.resolveSource(file.path), output, "Paragraphs", "es",
            translate = { text, target ->
                assertEquals("es", target)
                visited += text
                Result.success("Translated paragraph ${visited.lastIndex}")
            },
            onProgress = { done, total -> progress += done to total })
        assertEquals(205, visited.size)
        assertEquals("Native paragraph", visited.last())
        assertEquals((1..205).map { it to 205 }, progress.filter { it.first > 0 })
        assertEquals("md", result.extension)
        assertTrue(result.readText().endsWith("Translated paragraph 204\n"))
        assertEquals(205, result.readLines().count { it.contains("Translated paragraph") })
        assertTrue(output.listFiles()!!.none { it.extension == "part" })
    }

    @Test fun moreThanTwoHundredUnitsPublishOnlyAfterEverySuccessfulUnitAndKeepUniqueNames() = runTest {
        val progress = mutableListOf<Pair<Int, Int>>()
        val visited = mutableListOf<String>()
        val flow = coordinator { source, part, language, translate, report ->
            assertEquals(DocumentFormat.TXT, source.format)
            assertEquals("part", part.extension)
            part.bufferedWriter().use { writer ->
                for (unit in 0 until 205) {
                    assertTrue(output.listFiles()!!.none { it.extension == "txt" })
                    writer.appendLine(translate("unit $unit", language).getOrThrow())
                    report(unit + 1, 205)
                }
            }
        }
        val source = flow.resolveSource(File(root, "input.txt").apply { writeText("Native text") }.path)
        val result = flow.translateCopy(source, output, "Title", "es", translate = { text, language ->
            assertEquals("es", language)
            visited += text
            Result.success("translated $text")
        }, onProgress = { done, total -> progress += done to total })
        assertEquals((0 until 205).map { "unit $it" }, visited)
        assertEquals((1..205).map { it to 205 }, progress)
        assertEquals("translated unit 204", result.readLines().last())
        assertEquals(listOf(result), output.listFiles()!!.toList())
        val second = coordinator { _, part, _, _, _ -> part.writeText("Another complete result") }
            .translateCopy(source, output, "Title", "es", translate = { _, _ -> error("Unexpected provider") }, onProgress = { _, _ -> })
        assertNotEquals(result, second)
        assertEquals(205, result.readLines().size)
        assertEquals(2, output.listFiles()!!.size)
    }

    @Test fun dismissingSuspendedExportCleansOutputAndPropagatesCancellation() = runTest {
        val started = CompletableDeferred<Unit>()
        val flow = coordinator { _, part, language, translate, _ ->
            part.writeText("Temporary data")
            translate("Native text", language).getOrThrow()
        }
        val source = flow.resolveSource(File(root, "cancel.txt").apply { writeText("Native text") }.path)
        val task = async {
            flow.translateCopy(source, output, "Title", "es", translate = { _, _ ->
                started.complete(Unit)
                awaitCancellation()
            }, onProgress = { _, _ -> })
        }
        started.await()
        assertTrue(output.listFiles()!!.any { it.extension == "part" })
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(output.listFiles()!!.isEmpty())
    }

    @Test fun providerFailureBlankOutputAndCancellationCleanPartialAndNeverPublish() = runTest {
        for (kind in listOf("failure", "blank", "cancel")) {
            val visited = mutableListOf<String>()
            val cancelled = CancellationException("Dismissed")
            val providerError = IllegalStateException("offline")
            val flow = coordinator { _, part, language, translate, report ->
                part.bufferedWriter().use { writer ->
                    for (unit in 0 until 5) {
                        writer.appendLine(translate("unit $unit", language).getOrThrow())
                        report(unit + 1, 5)
                    }
                }
            }
            val source = flow.resolveSource(File(root, "input.txt").apply { writeText("Native text") }.path)
            val failure = runCatching {
                flow.translateCopy(source, output, "Title", "es", translate = { text, _ ->
                    visited += text
                    if (text != "unit 2") Result.success("translated") else when (kind) {
                        "failure" -> Result.failure(providerError)
                        "blank" -> Result.success(" ")
                        else -> Result.failure(cancelled)
                    }
                }, onProgress = { _, _ -> })
            }.exceptionOrNull()
            when (kind) {
                "failure" -> assertTrue(failure is IllegalStateException && failure.message == providerError.message)
                "blank" -> assertTrue(failure is TranslationRejectedException)
                else -> assertTrue(failure is CancellationException && failure.message == cancelled.message)
            }
            assertEquals(listOf("unit 0", "unit 1", "unit 2"), visited)
            assertTrue(output.listFiles()!!.isEmpty())
            assertTrue(cache.listFiles()!!.isEmpty())
        }
    }

    @Test fun contentNotIndexedFormatOrSuffixSelectsAdapterAndNativeOutputSuffix() = runTest {
        val routed = mutableListOf<DocumentFormat>()
        val flow = coordinator { source, part, _, _, _ -> routed += source.format; part.writeText("Complete native output") }
        val fixtures = listOf(
            File(root, "renamed.txt").apply { writeText("%PDF-1.7\nNative PDF fixture") } to DocumentFormat.PDF,
            archive("renamed.pdf", "META-INF/container.xml") to DocumentFormat.EPUB,
            archive("renamed.epub", "word/document.xml") to DocumentFormat.DOCX,
            File(root, "renamed.docx").apply { writeText("<FictionBook><body>Native</body></FictionBook>") } to DocumentFormat.FB2,
            File(root, "notes.md").apply { writeText("# Native markdown") } to DocumentFormat.TXT,
            File(root, "notes.log").apply { writeText("Native log text") } to DocumentFormat.TXT,
            File(root, "unicode.txt").apply {
                writeText("<fb:FictionBook xmlns:fb='http://www.gribuser.ru/xml/fictionbook/2.0'><fb:body>Native</fb:body></fb:FictionBook>", Charsets.UTF_16)
            } to DocumentFormat.FB2
        )
        for ((file, format) in fixtures) {
            val source = flow.resolveSource(file.path)
            assertEquals(format, source.format)
            val generated = flow.translateCopy(source, output, "Title", "es", translate = { _, _ -> error("Routing must not call provider") }, onProgress = { _, _ -> })
            assertEquals(if (format == DocumentFormat.TXT) file.extension else format.extension, generated.extension)
        }
        assertEquals(fixtures.map { it.second }, routed)
        assertEquals("TxtEngine", manager.getEngineForDocument(fixtures[4].first.path).javaClass.simpleName)
    }

    @Test fun unsupportedImageAndIndexedFormatsRejectBeforeAdapterOrProvider() = runTest {
        val flow = coordinator { _, _, _, _, _ -> error("Rejected source reached adapter") }
        val files = listOf(
            archive("comic.pdf", "images/page.png"),
            File(root, "scan.txt").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a)) },
            File(root, "indexed.doc").apply { writeBytes(byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte())) },
            File(root, "indexed.djvu").apply { writeText("AT&TFORM Native fixture") },
            File(root, "wrong.pdf").apply { writeText("Not a PDF") }
        )
        for (file in files) assertTrue(runCatching { flow.resolveSource(file.path) }.exceptionOrNull() is TranslationRejectedException)
    }

    @Test fun changedResolvedFormatRequiresFreshReviewBeforeProvider() = runTest {
        val flow = coordinator { _, _, _, _, _ -> error("Changed source reached adapter") }
        val file = File(root, "input.txt").apply { writeText("Native text") }
        val originalResolution = flow.resolveSource(file.path)
        file.writeText("%PDF-1.7\nNow a PDF")
        val error = runCatching {
            flow.translateCopy(originalResolution, output, "Title", "es", translate = { _, _ -> error("Unexpected provider") }, onProgress = { _, _ -> })
        }.exceptionOrNull()
        assertTrue(error is TranslationRejectedException)
        assertTrue(output.listFiles()!!.isEmpty())
    }

    @Test fun eachKindleExportRequiresConsentAndConversionCleanupIncludingFailure() = runTest {
        var conversions = 0
        var adapterCalls = 0
        val flow = coordinator(convert = { _, target ->
            conversions++
            writeArchive(target, "META-INF/container.xml")
            true
        }) { source, part, language, translate, _ ->
            adapterCalls++
            assertEquals(DocumentFormat.EPUB, source.format)
            part.writeText(translate("Native Kindle text", language).getOrThrow())
        }
        for (suffix in listOf("mobi", "azw", "azw3")) {
            val source = flow.resolveSource(File(root, "input.$suffix").apply {
                writeBytes(ByteArray(68).also { "BOOKMOBI".toByteArray().copyInto(it, 60) })
            }.path)
            val before = conversions
            val rejected = runCatching {
                flow.translateCopy(source, output, "Title", "es", translate = { _, _ -> error("Unconfirmed provider") }, onProgress = { _, _ -> })
            }.exceptionOrNull()
            assertTrue(rejected is TranslationRejectedException)
            assertEquals(before, conversions)
            val generated = flow.translateCopy(source, output, "Title", "es", kindleConversionConfirmed = true,
                translate = { _, _ -> Result.success("Translated Kindle") }, onProgress = { _, _ -> })
            assertEquals("epub", generated.extension)
            assertTrue(cache.listFiles()!!.isEmpty())
        }
        assertEquals(3, adapterCalls)
        val source = flow.resolveSource(File(root, "input.mobi").path)
        val beforeFailure = output.listFiles()!!.toSet()
        val cancelled = CancellationException("Cancelled conversion export")
        val failure = runCatching {
            flow.translateCopy(source, output, "Title", "es", true, { _, _ -> Result.failure(cancelled) }, { _, _ -> })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException && failure.message == cancelled.message)
        assertEquals(beforeFailure, output.listFiles()!!.toSet())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun invalidKindleConversionNeverReachesProvider() = runTest {
        val flow = coordinator(convert = { _, target -> target.writeText("Not EPUB"); true }) { _, _, _, _, _ -> error("Invalid conversion reached adapter") }
        val source = flow.resolveSource(File(root, "input.mobi").apply {
            writeBytes(ByteArray(68).also { "BOOKMOBI".toByteArray().copyInto(it, 60) })
        }.path)
        assertTrue(runCatching {
            flow.translateCopy(source, output, "Title", "es", true, { _, _ -> error("Unexpected provider") }, { _, _ -> })
        }.exceptionOrNull() is TranslationRejectedException)
        assertTrue(output.listFiles()!!.isEmpty())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    private fun archive(name: String, member: String) = File(root, name).also { writeArchive(it, member) }
    private fun writeArchive(file: File, member: String) {
        ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(ZipEntry(member)); it.write("Native routing fixture".toByteArray()); it.closeEntry()
        }
    }
}
