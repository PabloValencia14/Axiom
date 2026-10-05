package org.readera.openreadera.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readera.openreadera.engine.DocumentFormat
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class PackageDocumentTranslatorTest {
    private val translator get() = PackageDocumentTranslator(RuntimeEnvironment.getApplication())
    private val image = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")

    @Test fun docxPreservesTablesStylesLinksFieldsHeadersNotesDrawingsAndMemberHashes() = runBlocking {
        fixture("docx") { source, output ->
            val members = docxMembers()
            zip(source, members)
            val original = source.readBytes()
            val calls = mutableListOf<String>()
            val progress = mutableListOf<Pair<Int, Int>>()
            translator.translateCopy(resolved(source, DocumentFormat.DOCX, "docx"), output, "es", { text, language ->
                assertEquals("es", language); calls += text; Result.success("Traducido $text")
            }, { done, total -> progress += done to total })
            assertEquals(listOf("Hello world", "Bold", "Linked", "After", "Tab text", "Break text", "Cell text", "Drawing text", "Header text", "Footer text", "Footnote text", "Endnote text", "Comment text"), calls)
            assertEquals((0..calls.size).map { it to calls.size }, progress)
            val result = unzip(output)
            for ((name, bytes) in members.filterKeys { !it.matches(Regex("word/(document|header1|footer1|footnotes|endnotes|comments)\\.xml")) }) {
                assertArrayEquals("Untouched member $name", hash(bytes), hash(result.getValue(name)))
            }
            val document = result.getValue("word/document.xml").toString(Charsets.UTF_8)
            assertTrue(document.contains("<w:t xml:space=\"preserve\">Traducido Hello world</w:t>"))
            assertTrue(document.contains("<w:t></w:t>"))
            assertTrue(document.contains("<w:rPr><w:b/></w:rPr><w:t>Traducido Bold</w:t>"))
            assertTrue(document.contains("<w:hyperlink r:id=\"link\"><w:r><w:t>Traducido Linked</w:t>"))
            assertTrue(document.contains("<w:instrText xml:space=\"preserve\"> PAGE </w:instrText>"))
            assertTrue(document.contains("<w:t>7</w:t>"))
            assertTrue(document.contains("<w:fldSimple w:instr=\"REF bookmark\"><w:r><w:t>2</w:t>"))
            assertTrue(document.contains("<w:tblPr><w:tblStyle w:val=\"TableGrid\"/></w:tblPr>"))
            assertTrue(document.contains("<w:sectPr><w:headerReference w:type=\"default\" r:id=\"header\"/><w:footerReference w:type=\"default\" r:id=\"footer\"/><w:pgSz w:w=\"12240\" w:h=\"15840\"/></w:sectPr>"))
            assertTrue(document.contains("<a:t>Traducido Drawing text</a:t>"))
            val mask = Regex("(<(?:w|a):t(?:\\s[^>]*)?>)[\\s\\S]*?(</(?:w|a):t>)")
            for (name in listOf("word/document.xml", "word/header1.xml", "word/footer1.xml", "word/footnotes.xml", "word/endnotes.xml", "word/comments.xml")) {
                fun structure(bytes: ByteArray) = mask.replace(bytes.toString(Charsets.UTF_8)) { it.groupValues[1] + it.groupValues[2] }
                assertEquals("Only native text spans change in $name", structure(members.getValue(name)), structure(result.getValue(name)))
            }
            assertArrayEquals(original, source.readBytes())
            assertEquals(members.keys, result.keys)
        }
    }

    @Test fun epubRetainsPackageSpineNavCssImagesLinksAndFirstStoredMimetype() = runBlocking {
        fixture("epub") { source, output ->
            val members = epubMembers()
            zip(source, members, mimetypeLast = true)
            val original = source.readBytes()
            val calls = mutableListOf<String>()
            translator.translateCopy(resolved(source, DocumentFormat.EPUB, "epub"), output, "es", { text, _ ->
                calls += text; Result.success("Traducido $text")
            }, { _, _ -> })
            assertEquals(listOf("Chapter title", "Hello", "world", "ending", "Next chapter", "Contents", "Chapter link"), calls)
            val result = unzip(output)
            members.filterKeys { it !in setOf("OPS/chapter.xhtml", "OPS/nav.xhtml") }.forEach { (name, bytes) ->
                assertArrayEquals("Untouched EPUB member $name", hash(bytes), hash(result.getValue(name)))
            }
            val chapter = result.getValue("OPS/chapter.xhtml").toString(Charsets.UTF_8)
            assertTrue(chapter.contains("<p id=\"one\"> Traducido Hello <em>Traducido world</em> Traducido ending </p>"))
            assertTrue(chapter.contains("<a href=\"chapter.xhtml#one\">Traducido Next chapter</a>"))
            assertTrue(chapter.contains("<img src=\"image.png\" alt=\"illustration\"/>"))
            assertTrue(result.getValue("OPS/nav.xhtml").toString(Charsets.UTF_8).contains("href=\"chapter.xhtml#one\">Traducido Chapter link"))
            ZipFile(output).use { file ->
                val first = file.entries().nextElement()
                assertEquals("mimetype", first.name); assertEquals(ZipEntry.STORED, first.method)
                assertTrue(first.extra == null || first.extra.isEmpty())
            }
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun epubFontObfuscationMetadataAndOpaqueFontBytesRemainIdentical() = runBlocking {
        for (algorithm in listOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")) fixture("epub") { source, output ->
            val members = epubMembers().toMutableMap()
            val font = ByteArray(4096) { ((it * 31 + 17) and 255).toByte() }
            val encryption = """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:e="http://www.w3.org/2001/04/xmlenc#"><e:EncryptedData><e:EncryptionMethod Algorithm="$algorithm"/><e:CipherData><e:CipherReference URI="OPS/font.otf"/></e:CipherData></e:EncryptedData></encryption>""".toByteArray()
            members["OPS/font.otf"] = font
            members["META-INF/encryption.xml"] = encryption
            zip(source, members)
            val original = source.readBytes()
            translator.translateCopy(resolved(source, DocumentFormat.EPUB, "epub"), output, "es", { text, _ -> Result.success("Traducido $text") }, { _, _ -> })
            val result = unzip(output)
            assertArrayEquals(hash(font), hash(result.getValue("OPS/font.otf")))
            assertArrayEquals(encryption, result.getValue("META-INF/encryption.xml"))
            assertArrayEquals(members.getValue("OPS/package.opf"), result.getValue("OPS/package.opf"))
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun fb2PreservesMetadataSectionsIdsLinksAndEmbeddedBinaryWhileTranslatingNotes() = runBlocking {
        fixture("fb2") { source, output ->
            val binary = Base64.getEncoder().encodeToString(image)
            val xml = """<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns="$FB2_NS" xmlns:l="http://www.w3.org/1999/xlink"><description><title-info><author><first-name>Author</first-name></author><book-title>Book title</book-title><annotation><p>Annotation</p></annotation><lang>en</lang><coverpage><image l:href="#cover"/></coverpage></title-info><document-info><id>immutable-id</id><version>1.0</version></document-info></description><body><section id="chapter"><title><p>Chapter</p></title><p> Hello <emphasis>world</emphasis> ending <a l:href="#note" type="note">1</a></p><image l:href="#cover"/></section></body><body name="notes"><section id="note"><p>Note text</p></section></body><binary id="cover" content-type="image/png">$binary</binary></FictionBook>"""
            source.writeText(xml)
            val calls = mutableListOf<String>()
            translator.translateCopy(resolved(source, DocumentFormat.FB2, "fb2"), output, "es", { text, _ -> calls += text; Result.success("Traducido $text") }, { _, _ -> })
            assertEquals(listOf("Book title", "Annotation", "Chapter", "Hello", "world", "ending", "Note text"), calls)
            val translated = output.readText()
            assertTrue(translated.contains("<author><first-name>Author</first-name></author>"))
            assertTrue(translated.contains("<id>immutable-id</id><version>1.0</version>"))
            assertTrue(translated.contains("<section id=\"note\"><p>Traducido Note text</p>"))
            assertTrue(translated.contains("<a l:href=\"#note\" type=\"note\">1</a>"))
            assertTrue(translated.contains("<binary id=\"cover\" content-type=\"image/png\">$binary</binary>"))
            assertEquals(xml, source.readText())
        }
    }

    @Test fun txtRetainsUtf8AndUtf16BomsMixedNewlinesBlankLinesAndOuterWhitespace() = runBlocking {
        for ((bom, charset) in listOf(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) to Charsets.UTF_8,
            byteArrayOf(0xff.toByte(), 0xfe.toByte()) to Charsets.UTF_16LE,
            byteArrayOf(0xfe.toByte(), 0xff.toByte()) to Charsets.UTF_16BE)) {
            fixture("txt") { source, output ->
                val original = bom + "  Hello  \r\n\r\nWorld\n123\rLast\tColumn".toByteArray(charset)
                source.writeBytes(original)
                val calls = mutableListOf<String>()
                translator.translateCopy(resolved(source, DocumentFormat.TXT, "txt"), output, "es", { text, _ -> calls += text; Result.success("Traducido $text") }, { _, _ -> })
                assertEquals(listOf("Hello", "World", "Last", "Column"), calls)
                assertArrayEquals(bom + "  Traducido Hello  \r\n\r\nTraducido World\n123\rTraducido Last\tTraducido Column".toByteArray(charset), output.readBytes())
                assertArrayEquals(original, source.readBytes())
            }
        }
    }

    @Test fun declaredLegacyXmlEncodingIsStrictAndRetainedWithoutSourceMutation() = runBlocking {
        fixture("fb2") { source, output ->
            val charset = java.nio.charset.Charset.forName("windows-1251")
            val xml = "<?xml version=\"1.0\" encoding=\"windows-1251\"?><FictionBook xmlns=\"$FB2_NS\"><body><section><p>Привет</p></section></body><binary id=\"image\" content-type=\"image/png\">${Base64.getEncoder().encodeToString(image)}</binary></FictionBook>"
            source.writeBytes(xml.toByteArray(charset))
            val original = source.readBytes()
            translator.translateCopy(resolved(source, DocumentFormat.FB2, "fb2"), output, "ru", { text, _ ->
                assertEquals("Привет", text); Result.success("Перевод")
            }, { _, _ -> })
            assertArrayEquals(xml.replace("Привет", "Перевод").toByteArray(charset), output.readBytes())
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun unrepresentableLegacyXmlTranslationFailsWithoutChangingOriginalEncoding() = runBlocking {
        fixture("fb2") { source, output ->
            val original = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><FictionBook xmlns=\"$FB2_NS\"><body><p>Hello</p></body></FictionBook>".toByteArray(Charsets.ISO_8859_1)
            source.writeBytes(original)
            val failure = captureFailure {
                translator.translateCopy(resolved(source, DocumentFormat.FB2, "fb2"), output, "ja", { _, _ -> Result.success("日本語") }, { _, _ -> })
            }
            assertTrue(failure is TranslationRejectedException)
            assertFalse(output.exists()); assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun xmlEntitiesCdataAndNamespaceEquivalentStylesRetainOriginalMarkup() = runBlocking {
        fixture("docx") { source, output ->
            val members = docxMembers().toMutableMap()
            members["word/document.xml"] = """<w:document xmlns:w="$WORD_NS" xmlns:x="$WORD_NS"><w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve"> Hello &amp; </w:t></w:r><w:r><x:rPr> <x:b /> </x:rPr><w:t><![CDATA[world]]></w:t></w:r><w:r><w:rPr><w:i/></w:rPr><w:t>Italic</w:t></w:r><w:r><w:t>42</w:t></w:r></w:p></w:body></w:document>""".toByteArray()
            // Keep referenced parts valid but with no selected narrative units in this focused fixture.
            for (name in listOf("header1", "footer1", "footnotes", "endnotes", "comments")) members["word/$name.xml"] = "<w:p xmlns:w=\"$WORD_NS\"/>".toByteArray()
            zip(source, members)
            val original = source.readBytes()
            val calls = mutableListOf<String>()
            translator.translateCopy(resolved(source, DocumentFormat.DOCX, "docx"), output, "es", { text, _ -> calls += text; Result.success("Nuevo <&>") }, { _, _ -> })
            assertEquals(listOf("Hello & world", "Italic"), calls)
            val translated = unzip(output).getValue("word/document.xml").toString(Charsets.UTF_8)
            assertTrue(translated.contains("<w:t xml:space=\"preserve\"> Nuevo &lt;&amp;&gt;</w:t>"))
            assertTrue(translated.contains("<x:rPr> <x:b /> </x:rPr><w:t></w:t>"))
            assertTrue(translated.contains("<w:r><w:t>42</w:t></w:r>"))
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun markdownRetainsFencesCodeSyntaxLinkDestinationsReferencesAndTaskMarkers() = runBlocking {
        fixture("md") { source, output ->
            val markdown = "# Heading\r\n\r\nHello **bold** [label](https://example.test/a_(b) \"Title (x)\") and `code_value` [ref][id].\r\n- [x] Task\r\n\r\n[id]: https://example.test/ref \"Reference\"\r\n```kotlin\r\nval message = \"do not translate\"\r\n```\r\n    indented code\r\n"
            source.writeText(markdown)
            val calls = mutableListOf<String>()
            translator.translateCopy(resolved(source, DocumentFormat.TXT, "md"), output, "es", { text, _ -> calls += text; Result.success(text.uppercase()) }, { _, _ -> })
            assertEquals(listOf("Heading", "Hello", "bold", "label", "and", "ref", "Task"), calls)
            assertEquals(markdown.replace("Heading", "HEADING").replace("Hello", "HELLO").replace("**bold**", "**BOLD**").replace("[label]", "[LABEL]").replace(" and ", " AND ").replace("[ref][id]", "[REF][id]").replace("Task", "TASK"), output.readText())
            assertEquals(markdown, source.readText())
        }
    }

    @Test fun logRetainsTimestampsLevelsThreadIdsUrlsPathsAndMachineFields() = runBlocking {
        fixture("log") { source, output ->
            val log = "2026-10-04T12:30:00.125Z INFO [worker-1] User connected user_id=42 host=\"node one\" elapsed=12ms request=550e8400-e29b-41d4-a716-446655440000\r\n2026-10-04 12:31:01 WARN Retry request https://example.test/item/1 /var/log/app.log count=2\r\n"
            source.writeText(log)
            val calls = mutableListOf<String>()
            translator.translateCopy(resolved(source, DocumentFormat.TXT, "log"), output, "es", { text, _ -> calls += text; Result.success(text.uppercase()) }, { _, _ -> })
            assertEquals(listOf("User connected", "Retry request"), calls)
            assertEquals(log.replace("User connected", "USER CONNECTED").replace("Retry request", "RETRY REQUEST"), output.readText())
            assertEquals(log, source.readText())
        }
    }

    @Test fun markdownImplicitReferencesFootnoteIdsAndExplicitAnchorsKeepTheirTargets() = runBlocking {
        fixture("md") { source, output ->
            val markdown = "# Heading {#original-anchor}\r\n[Link][] and [Shortcut] and text[^note].\r\n\r\n[Link]: https://example.test/one\r\n[Shortcut]: https://example.test/two\r\n[^note]: Footnote text\r\n"
            source.writeText(markdown)
            val original = source.readBytes()
            val calls = mutableListOf<String>()
            translator.translateCopy(resolved(source, DocumentFormat.TXT, "md"), output, "es", { text, _ -> calls += text; Result.success(text.uppercase()) }, { _, _ -> })
            assertEquals(listOf("Heading", "Link", "and", "Shortcut", "and text", "Footnote text"), calls)
            assertEquals("# HEADING {#original-anchor}\r\n[LINK][Link] AND [SHORTCUT][Shortcut] AND TEXT[^note].\r\n\r\n[Link]: https://example.test/one\r\n[Shortcut]: https://example.test/two\r\n[^note]: FOOTNOTE TEXT\r\n", output.readText())
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun traversalInvalidChecksumAndXmlDepthRejectBeforeAnyTranslation() = runBlocking {
        for (case in listOf("traversal", "crc", "depth")) fixture("docx") { source, output ->
            val members = docxMembers().toMutableMap()
            if (case == "traversal") members["../outside.xml"] = "<outside/>".toByteArray()
            if (case == "depth") members["word/document.xml"] = ("<w:document xmlns:w=\"$WORD_NS\">" + "<w:p>".repeat(129) + "Text" + "</w:p>".repeat(129) + "</w:document>").toByteArray()
            zip(source, members)
            if (case == "crc") {
                val bytes = source.readBytes()
                val central = (0 until bytes.size - 20).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 0x01.toByte() && bytes[it + 3] == 0x02.toByte() }
                bytes[central + 16] = (bytes[central + 16].toInt() xor 1).toByte()
                source.writeBytes(bytes)
            }
            val original = source.readBytes()
            var calls = 0
            captureFailure { translator.translateCopy(resolved(source, DocumentFormat.DOCX, "docx"), output, "es", { _, _ -> calls++; Result.success("Never") }, { _, _ -> }) }
            assertEquals(0, calls); assertFalse(output.exists()); assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun providerFailureBlankAndCancellationNeverProducePartialCopies() = runBlocking {
        for (format in listOf("txt", "docx", "epub", "fb2")) for (mode in listOf("failure", "blank", "cancel", "cancel-result")) {
            fixture(format) { source, output ->
                val documentFormat = when (format) {
                    "docx" -> { zip(source, docxMembers()); DocumentFormat.DOCX }
                    "epub" -> { zip(source, epubMembers()); DocumentFormat.EPUB }
                    "fb2" -> { source.writeText("<FictionBook xmlns=\"$FB2_NS\"><body><section><p>First paragraph</p><p>Second paragraph</p></section></body></FictionBook>"); DocumentFormat.FB2 }
                    else -> { source.writeText("First paragraph\r\n\r\nSecond paragraph\r\n"); DocumentFormat.TXT }
                }
                val original = source.readBytes()
                var calls = 0
                val failure = captureFailure {
                    translator.translateCopy(resolved(source, documentFormat, format), output, "es", { text, _ ->
                        calls++
                        if (calls == 2) when (mode) {
                            "failure" -> Result.failure(IOException("Provider failed"))
                            "blank" -> Result.success("   ")
                            "cancel-result" -> Result.failure(CancellationException("Cancelled provider result"))
                            else -> throw CancellationException("Cancelled")
                        } else Result.success("Traducido $text")
                    }, { _, _ -> })
                }
                assertEquals(2, calls)
                assertFalse(output.exists())
                assertArrayEquals(original, source.readBytes())
                when (mode) {
                    "failure" -> assertTrue(failure is IOException)
                    "blank" -> assertTrue(failure is TranslationRejectedException)
                    else -> assertTrue(failure is CancellationException)
                }
            }
        }
    }

    @Test fun cancellationAfterLastUnitDoesNotProduceOutput() = runBlocking {
        fixture("docx") { source, output ->
            zip(source, docxMembers())
            val original = source.readBytes()
            // This observes a cancellation after all provider units, before publication.
            val failure = captureFailure {
                translator.translateCopy(resolved(source, DocumentFormat.DOCX, "docx"), output, "es", { text, _ -> Result.success("Traducido $text") }, { done, total ->
                    if (done == total) throw CancellationException("Cancelled after translation")
                })
            }
            assertTrue(failure is CancellationException)
            assertFalse(output.exists())
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun serializationExpansionFailureDeletesAlreadyCreatedPartOutput() = runBlocking {
        fixture("docx") { source, output ->
            val members = docxMembers().filterKeys { it !in setOf("word/header1.xml", "word/footer1.xml", "word/footnotes.xml", "word/endnotes.xml", "word/comments.xml") }.toMutableMap()
            members["word/document.xml"] = "<w:document xmlns:w=\"$WORD_NS\"><w:body><w:p><w:r><w:t>Hello</w:t></w:r></w:p></w:body></w:document>".toByteArray()
            members["[Content_Types].xml"] = "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>".toByteArray()
            zip(source, members)
            val original = source.readBytes()
            val failure = captureFailure {
                translator.translateCopy(resolved(source, DocumentFormat.DOCX, "docx"), output, "es", { _, _ -> Result.success("x".repeat(NATIVE_TEXT_LIMIT.toInt())) }, { _, _ -> })
            }
            assertTrue(failure is TranslationRejectedException)
            assertFalse(output.exists())
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun unsupportedVisibleContentMalformedXmlXxeAndStructuredStreamsRejectBeforeProvider() = runBlocking {
        val unsupported = listOf(
            "docx-altchunk", "docx-chart", "docx-tracked", "docx-field-result", "epub-script", "epub-css", "epub-spine", "epub-encryption",
            "fb2-xxe", "fb2-foreign", "fb2-malformed", "txt-json", "txt-invalid-utf8", "md-html", "md-fence", "log-json", "log-ambiguous"
        )
        for (case in unsupported) fixture(case.substringBefore('-')) { source, output ->
            val format = when {
                case.startsWith("docx") -> {
                    val members = docxMembers().toMutableMap()
                    val extra = when (case) {
                        "docx-altchunk" -> "<w:altChunk xmlns:w=\"$WORD_NS\"/>"
                        "docx-chart" -> "<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:v>Visible chart title</c:v></c:chartSpace>"
                        "docx-field-result" -> "<w:fldSimple xmlns:w=\"$WORD_NS\" w:instr=\"TOC\"><w:r><w:t>Visible TOC title</w:t></w:r></w:fldSimple>"
                        else -> "<w:ins xmlns:w=\"$WORD_NS\"><w:r><w:t>Tracked text</w:t></w:r></w:ins>"
                    }
                    members["word/unsupported.xml"] = extra.toByteArray(); zip(source, members); DocumentFormat.DOCX
                }
                case.startsWith("epub") -> {
                    val members = epubMembers().toMutableMap()
                    when (case) {
                        "epub-script" -> members["OPS/chapter.xhtml"] = members.getValue("OPS/chapter.xhtml").toString(Charsets.UTF_8).replace("</body>", "<script>alert('x')</script></body>").toByteArray()
                        "epub-css" -> members["OPS/style.css"] = "p::before { content: 'Visible'; }".toByteArray()
                        "epub-spine" -> members["OPS/package.opf"] = members.getValue("OPS/package.opf").toString(Charsets.UTF_8).replace("application/xhtml+xml", "image/svg+xml").toByteArray()
                        else -> members["META-INF/encryption.xml"] = "<encryption/>".toByteArray()
                    }
                    zip(source, members); DocumentFormat.EPUB
                }
                case.startsWith("fb2") -> {
                    val value = when (case) {
                        "fb2-xxe" -> "<!DOCTYPE FictionBook [<!ENTITY secret SYSTEM 'file:///not-a-real-secret'>]><FictionBook xmlns=\"$FB2_NS\"><body><p>&secret;</p></body></FictionBook>"
                        "fb2-foreign" -> "<FictionBook xmlns=\"$FB2_NS\"><body><foreign xmlns=\"urn:foreign\">Visible text</foreign></body></FictionBook>"
                        else -> "<FictionBook xmlns=\"$FB2_NS\"><body><p>Broken</body></FictionBook>"
                    }
                    source.writeText(value); DocumentFormat.FB2
                }
                else -> {
                    val value = when (case) {
                        "txt-json" -> "{\"message\":\"Hello\"}"
                        "md-html" -> "<span>Hello</span>"
                        "md-fence" -> "```\ncode\n"
                        "log-json" -> "2026-10-04T12:00:00Z INFO {\"message\":\"Hello\"}"
                        "log-ambiguous" -> "INFO message without timestamp"
                        else -> ""
                    }
                    if (case == "txt-invalid-utf8") source.writeBytes(byteArrayOf(0xc3.toByte(), 0x28)) else source.writeText(value)
                    DocumentFormat.TXT
                }
            }
            val original = source.readBytes()
            var calls = 0
            captureFailure { translator.translateCopy(resolved(source, format, case.substringBefore('-')), output, "es", { _, _ -> calls++; Result.success("Never") }, { _, _ -> }) }
            assertEquals("Preflight case $case", 0, calls)
            assertFalse(output.exists())
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun textExpansionEntryCountAndDocumentBudgetsRejectBeforeProvider() = runBlocking {
        for (case in listOf("text", "expanded", "package-expanded", "entries", "document")) fixture(if (case == "text") "txt" else "docx") { source, output ->
            val block = ByteArray(1024 * 1024) { 'x'.code.toByte() }
            when (case) {
                "text" -> source.outputStream().use { out -> repeat(17) { out.write(block) } }
                "expanded" -> ZipOutputStream(source.outputStream()).use { out ->
                    out.putNextEntry(ZipEntry("word/document.xml")); repeat(17) { out.write(block) }; out.closeEntry()
                }
                "package-expanded" -> ZipOutputStream(source.outputStream()).use { out ->
                    out.putNextEntry(ZipEntry("large.bin")); repeat(257) { out.write(block) }; out.closeEntry()
                }
                "entries" -> ZipOutputStream(source.outputStream()).use { out -> repeat(NATIVE_ENTRY_LIMIT + 1) { out.putNextEntry(ZipEntry("entry-$it")); out.closeEntry() } }
                else -> java.io.RandomAccessFile(source, "rw").use { it.setLength(TransferDocumentLimit + 1) }
            }
            var calls = 0
            captureFailure {
                translator.translateCopy(resolved(source, if (case == "text") DocumentFormat.TXT else DocumentFormat.DOCX, if (case == "text") "txt" else "docx"), output, "es", { _, _ -> calls++; Result.success("Never") }, { _, _ -> })
            }
            assertEquals(0, calls); assertFalse(output.exists())
        }
    }

    @Test fun outputCannotOverwriteSourceOrExistingFileAndNewlineInjectionIsRejected() = runBlocking {
        fixture("txt") { source, output ->
            source.writeText("Hello\r\n")
            val original = source.readBytes()
            var calls = 0
            val provider: NativeTextTranslator = { _, _ -> calls++; Result.success("Hola\nExtra") }
            captureFailure { translator.translateCopy(resolved(source, DocumentFormat.TXT, "txt"), source, "es", provider, { _, _ -> }) }
            output.writeText("Existing user file")
            captureFailure { translator.translateCopy(resolved(source, DocumentFormat.TXT, "txt"), output, "es", provider, { _, _ -> }) }
            assertEquals(0, calls); assertEquals("Existing user file", output.readText())
            output.delete()
            captureFailure { translator.translateCopy(resolved(source, DocumentFormat.TXT, "txt"), output, "es", provider, { _, _ -> }) }
            assertEquals(1, calls); assertFalse(output.exists()); assertArrayEquals(original, source.readBytes())
        }
    }

    private fun docxMembers(): LinkedHashMap<String, ByteArray> {
        val ns = "xmlns:w=\"$WORD_NS\" xmlns:a=\"$DRAWING_NS\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
        val document = """<w:document $ns><w:body><w:p><w:pPr><w:pStyle w:val="Normal"/></w:pPr><w:r><w:t xml:space="preserve">Hello </w:t></w:r><w:r><w:t>world</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>Bold</w:t></w:r><w:hyperlink r:id="link"><w:r><w:t>Linked</w:t></w:r></w:hyperlink><w:r><w:t>After</w:t><w:tab/><w:t>Tab text</w:t><w:br/><w:t>Break text</w:t></w:r><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>7</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r><w:fldSimple w:instr="REF bookmark"><w:r><w:t>Bookmark result</w:t></w:r></w:fldSimple></w:p><w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/></w:tblPr><w:tblGrid><w:gridCol w:w="5000"/></w:tblGrid><w:tr><w:tc><w:tcPr><w:tcW w:w="5000" w:type="dxa"/></w:tcPr><w:p><w:r><w:t>Cell text</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:r><w:drawing><a:graphic><a:graphicData><a:p><a:r><a:t>Drawing text</a:t></a:r></a:p><a:blip r:embed="image"/></a:graphicData></a:graphic></w:drawing></w:r></w:p><w:sectPr><w:pgSz w:w="12240" w:h="15840"/></w:sectPr></w:body></w:document>"""
        fun story(root: String, text: String) = "<w:$root $ns><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:$root>".toByteArray()
        val parts = listOf("header1" to "header", "footer1" to "footer", "footnotes" to "footnotes", "endnotes" to "endnotes", "comments" to "comments", "styles" to "styles")
        val overrides = parts.joinToString("") { (part, type) -> "<Override PartName=\"/word/$part.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.$type+xml\"/>" }
        val relationships = parts.joinToString("") { (part, type) -> "<Relationship Id=\"$type\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/$type\" Target=\"$part.xml\"/>" }
        val linkedDocument = document.replace("<w:sectPr>", "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"header\"/><w:footerReference w:type=\"default\" r:id=\"footer\"/>").replace("<w:t>Bookmark result</w:t>", "<w:t>2</w:t>")
        return linkedMapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="png" ContentType="image/png"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>$overrides</Types>""".toByteArray(),
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="document" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""".toByteArray(),
            "word/document.xml" to linkedDocument.toByteArray(),
            "word/header1.xml" to story("hdr", "Header text"), "word/footer1.xml" to story("ftr", "Footer text"),
            "word/footnotes.xml" to "<w:footnotes $ns><w:footnote w:id=\"1\"><w:p><w:r><w:t>Footnote text</w:t></w:r></w:p></w:footnote></w:footnotes>".toByteArray(),
            "word/endnotes.xml" to "<w:endnotes $ns><w:endnote w:id=\"1\"><w:p><w:r><w:t>Endnote text</w:t></w:r></w:p></w:endnote></w:endnotes>".toByteArray(),
            "word/comments.xml" to "<w:comments $ns><w:comment w:id=\"1\"><w:p><w:r><w:t>Comment text</w:t></w:r></w:p></w:comment></w:comments>".toByteArray(),
            "word/styles.xml" to "<w:styles $ns><w:style w:type=\"paragraph\" w:styleId=\"Normal\"><w:rPr><w:sz w:val=\"22\"/></w:rPr></w:style></w:styles>".toByteArray(),
            "word/_rels/document.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="image" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image.png"/><Relationship Id="link" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://example.test" TargetMode="External"/>$relationships</Relationships>""".toByteArray(),
            "word/media/image.png" to image
        )
    }

    private fun epubMembers() = linkedMapOf(
        "mimetype" to "application/epub+zip".toByteArray(),
        "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
        "OPS/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">fixture</dc:identifier><dc:title>Original title</dc:title><dc:language>en</dc:language></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="css" href="style.css" media-type="text/css"/><item id="image" href="image.png" media-type="image/png"/></manifest><spine><itemref idref="chapter"/></spine></package>""".toByteArray(),
        "OPS/chapter.xhtml" to """<html xmlns="$XHTML_NS"><head><title>Chapter title</title><link rel="stylesheet" href="style.css"/></head><body><p id="one"> Hello <em>world</em> ending </p><a href="chapter.xhtml#one">Next chapter</a><img src="image.png" alt="illustration"/></body></html>""".toByteArray(),
        "OPS/nav.xhtml" to """<html xmlns="$XHTML_NS" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml#one">Chapter link</a></li></ol></nav></body></html>""".toByteArray(),
        "OPS/style.css" to "body { color: #112233; } em { font-style: italic; }".toByteArray(),
        "OPS/image.png" to image
    )

    private fun zip(file: File, members: Map<String, ByteArray>, mimetypeLast: Boolean = false) {
        ZipOutputStream(file.outputStream()).use { output ->
            val entries = if (mimetypeLast) members.entries.filter { it.key != "mimetype" } + members.entries.filter { it.key == "mimetype" } else members.entries.toList()
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                if (name == "mimetype") { entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.crc = CRC32().apply { update(bytes) }.value }
                output.putNextEntry(entry); output.write(bytes); output.closeEntry()
            }
        }
    }
    private fun unzip(file: File): Map<String, ByteArray> = ZipFile(file).use { zip -> zip.entries().toList().associate { it.name to zip.getInputStream(it).use { stream -> stream.readBytes() } } }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun resolved(file: File, format: DocumentFormat, extension: String) = ResolvedTranslationSource(file, format, extension)
    private suspend fun captureFailure(action: suspend () -> Unit): Throwable {
        try { action() } catch (failure: Throwable) { return failure }
        throw AssertionError("Expected native translation rejection")
    }
    private suspend fun fixture(extension: String, action: suspend (File, File) -> Unit) {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "native-package-${System.nanoTime()}").apply { mkdirs() }
        try { action(File(directory, "source.$extension"), File(directory, "translated.$extension.part")) }
        finally { directory.deleteRecursively() }
    }
    private companion object { const val TransferDocumentLimit = 256L * 1024 * 1024 }
}
