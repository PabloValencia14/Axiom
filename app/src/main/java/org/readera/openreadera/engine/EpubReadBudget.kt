package org.readera.openreadera.engine

import org.readera.openreadera.core.io.TransferLimits
import org.readera.openreadera.core.io.copyBounded
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Limits decompressed input before decoding or regex parsing, including repeated spine reads. */
internal class EpubReadBudget(
    private val memberBytes: Long = 2L * 1024 * 1024,
    private val totalBytes: Long = 16L * 1024 * 1024,
    private val entryCount: Int = 10_000,
    private val spineCount: Int = 2_000
) {
    private var remaining = totalBytes
    private var spineItems = 0
    private var manifestItems = 0
    private var retainedCharacters = 0L

    fun checkArchive(zip: ZipFile) {
        require(zip.size() <= entryCount) { "EPUB has too many entries" }
    }

    fun addManifestItem() {
        require(manifestItems < entryCount) { "EPUB has too many manifest items" }
        manifestItems++
    }

    fun addSpineItem() {
        require(spineItems < spineCount) { "EPUB has too many spine items" }
        spineItems++
    }

    fun readText(zip: ZipFile, entry: ZipEntry): String {
        val limit = minOf(memberBytes, remaining)
        TransferLimits.checkAdvertised(entry.size, limit)
        val output = ByteArrayOutputStream()
        val consumed = zip.getInputStream(entry).use { copyBounded(it, output, limit) }
        remaining -= consumed
        return output.toString(Charsets.UTF_8.name())
    }

    fun retainText(text: String) {
        require(text.length.toLong() <= totalBytes - retainedCharacters) { "EPUB text budget exhausted" }
        retainedCharacters += text.length
    }
}
