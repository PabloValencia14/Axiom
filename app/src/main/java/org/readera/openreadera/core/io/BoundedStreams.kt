package org.readera.openreadera.core.io

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Byte limits match OPDS documents; covers and JSON have separate, smaller budgets. */
internal object TransferLimits {
    const val DOCUMENT = 256L * 1024 * 1024
    const val COVER = 8L * 1024 * 1024
    const val SNAPSHOT = 16L * 1024 * 1024
    const val CATALOG_PAGE = 4L * 1024 * 1024

    fun checkAdvertised(size: Long, limit: Long) {
        require(size < 0 || size <= limit) { "Response too large" }
    }
}

internal fun copyBounded(
    input: InputStream,
    output: OutputStream,
    limit: Long,
    expectedSize: Long? = null,
    onCopied: (Long) -> Unit = {}
): Long {
    require(limit >= 0 && (expectedSize == null || expectedSize in 0..limit)) { "Invalid stream size" }
    val maximum = expectedSize ?: limit
    var total = 0L
    val buffer = ByteArray(8192)
    while (true) {
        // Read at most one excess byte to detect a dishonest length without consuming the stream.
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maximum - total + 1).toInt())
        if (count < 0) break
        if (count == 0) continue
        require(count.toLong() <= maximum - total) { "Response too large" }
        output.write(buffer, 0, count)
        total += count
        onCopied(total)
    }
    require(expectedSize == null || total == expectedSize) { "Stream size mismatch" }
    return total
}

internal fun InputStream.readBoundedText(limit: Long, advertisedSize: Long = -1): String {
    return use { input ->
        TransferLimits.checkAdvertised(advertisedSize, limit)
        val output = ByteArrayOutputStream()
        copyBounded(input, output, limit)
        output.toString(Charsets.UTF_8.name())
    }
}
