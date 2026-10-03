package org.readera.openreadera.engine

import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object EpubWriter {

    /**
     * Generates a standard, valid EPUB archive containing the given [chapters].
     * Each chapter is a Pair of (chapterTitle, textContent).
     */
    fun createEpub(
        outputFile: File,
        title: String,
        author: String,
        chapters: List<Pair<String, String>>,
        language: String = "es"
    ): Result<File> {
        return try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            val fos = FileOutputStream(outputFile)
            val zos = ZipOutputStream(fos)

            // 1. mimetype (MUST be stored uncompressed, NO_COMPRESSION)
            val mimetypeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val mimetypeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetypeBytes.size.toLong()
                compressedSize = mimetypeBytes.size.toLong()
                val crc = CRC32()
                crc.update(mimetypeBytes)
                setCrc(crc.value)
            }
            zos.putNextEntry(mimetypeEntry)
            zos.write(mimetypeBytes)
            zos.closeEntry()

            // 2. META-INF/container.xml
            val containerXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent()
            writeZipEntry(zos, "META-INF/container.xml", containerXml)

            // 3. OEBPS/style.css
            val styleCss = """
                body {
                    font-family: serif;
                    font-size: 1.05em;
                    line-height: 1.6;
                    margin: 5%;
                    color: #1a1a1a;
                }
                h1, h2 {
                    font-family: sans-serif;
                    color: #0f172a;
                    margin-bottom: 0.8em;
                    text-align: center;
                }
                p {
                    margin-bottom: 0.8em;
                    text-indent: 1.2em;
                }
                .translated-badge {
                    text-align: center;
                    font-size: 0.85em;
                    color: #64748b;
                    margin-bottom: 2em;
                    font-style: italic;
                }
            """.trimIndent()
            writeZipEntry(zos, "OEBPS/style.css", styleCss)

            // 4. Chapters XHTML
            val manifestItems = StringBuilder()
            val spineItems = StringBuilder()
            val tocNavPoints = StringBuilder()

            chapters.forEachIndexed { idx, (chapTitle, chapText) ->
                val chapId = "chap_${idx + 1}"
                val chapFileName = "chapter_${idx + 1}.xhtml"

                manifestItems.append("""    <item id="$chapId" href="$chapFileName" media-type="application/xhtml+xml"/>${"\n"}""")
                spineItems.append("""    <itemref idref="$chapId"/>${"\n"}""")

                tocNavPoints.append("""
                    <navPoint id="np_${idx + 1}" playOrder="${idx + 1}">
                        <navLabel><text>${escapeXml(chapTitle)}</text></navLabel>
                        <content src="$chapFileName"/>
                    </navPoint>
                """.trimIndent()).append("\n")

                // Convert plain text paragraphs into HTML
                val bodyHtml = StringBuilder()
                bodyHtml.append("<h2>${escapeXml(chapTitle)}</h2>\n")
                if (idx == 0) {
                    bodyHtml.append("<div class=\"translated-badge\">Traducción generada por Axiom</div>\n")
                }

                chapText.split("\n\n").forEach { p ->
                    val cleanP = p.trim()
                    if (cleanP.isNotBlank()) {
                        bodyHtml.append("<p>${escapeXml(cleanP)}</p>\n")
                    }
                }

                val xhtmlContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE html>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                    <head>
                        <title>${escapeXml(chapTitle)}</title>
                        <link rel="stylesheet" type="text/css" href="style.css"/>
                    </head>
                    <body>
                    $bodyHtml
                    </body>
                    </html>
                """.trimIndent()

                writeZipEntry(zos, "OEBPS/$chapFileName", xhtmlContent)
            }

            // 5. OEBPS/toc.ncx
            val tocNcx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                    <head>
                        <meta name="dtb:uid" content="urn:uuid:openreadera-trans-${System.currentTimeMillis()}"/>
                        <meta name="dtb:depth" content="1"/>
                        <meta name="dtb:totalPageCount" content="0"/>
                        <meta name="dtb:maxPageNumber" content="0"/>
                    </head>
                    <docTitle><text>${escapeXml(title)}</text></docTitle>
                    <docAuthor><text>${escapeXml(author)}</text></docAuthor>
                    <navMap>
                    $tocNavPoints
                    </navMap>
                </ncx>
            """.trimIndent()
            writeZipEntry(zos, "OEBPS/toc.ncx", tocNcx)

            // 6. OEBPS/content.opf
            val contentOpf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="2.0">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                        <dc:title>${escapeXml(title)}</dc:title>
                        <dc:creator opf:role="aut">${escapeXml(author)}</dc:creator>
                        <dc:language>$language</dc:language>
                        <dc:identifier id="BookId">urn:uuid:axiom-trans-${System.currentTimeMillis()}</dc:identifier>
                        <dc:publisher>Axiom</dc:publisher>
                    </metadata>
                    <manifest>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="css" href="style.css" media-type="text/css"/>
                    $manifestItems
                    </manifest>
                    <spine toc="ncx">
                    $spineItems
                    </spine>
                </package>
            """.trimIndent()
            writeZipEntry(zos, "OEBPS/content.opf", contentOpf)

            zos.finish()
            zos.close()
            fos.close()

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun writeZipEntry(zos: ZipOutputStream, entryName: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
