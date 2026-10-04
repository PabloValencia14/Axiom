package org.readera.openreadera.core.io

import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class BoundedStreamsTest {
    @Test fun advertisedAndActualLimitsRejectBeforeExcessWrites() {
        assertThrows(IllegalArgumentException::class.java) { TransferLimits.checkAdvertised(5, 4) }
        TransferLimits.checkAdvertised(-1, 4)
        TransferLimits.checkAdvertised(4, 4)
        val output = ByteArrayOutputStream()
        var consumed = 0
        val endless = object : InputStream() {
            override fun read(): Int { consumed++; return 65 }
        }
        assertThrows(IllegalArgumentException::class.java) { copyBounded(endless, output, 4) }
        assertEquals(5, consumed)
        assertTrue(output.size() <= 4)
        assertEquals(4, "book".byteInputStream().readBoundedText(4).length)
        assertThrows(IllegalArgumentException::class.java) { "books".byteInputStream().readBoundedText(4) }
    }

    @Test fun attachmentExpectedLengthRejectsLongAndShortStreamsAndAcceptsExact() {
        val output = ByteArrayOutputStream()
        assertEquals(4L, copyBounded("book".byteInputStream(), output, 100, 4))
        assertEquals("book", output.toString("UTF-8"))
        assertThrows(IllegalArgumentException::class.java) { copyBounded("books".byteInputStream(), ByteArrayOutputStream(), 100, 4) }
        assertThrows(IllegalArgumentException::class.java) { copyBounded("boo".byteInputStream(), ByteArrayOutputStream(), 100, 4) }
    }

    @Test fun oversizedAdvertisedSnapshotClosesItsAlreadyOpenedStreamWithoutReading() {
        var closed = false
        var read = false
        val input = object : InputStream() {
            override fun read(): Int { read = true; return -1 }
            override fun close() { closed = true }
        }
        assertThrows(IllegalArgumentException::class.java) { input.readBoundedText(4, 5) }
        assertTrue(closed)
        assertFalse(read)
    }
}
