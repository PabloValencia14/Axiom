package org.readera.openreadera.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class OpdsBoundedStreamTest {
    @Test fun streamLimitAcceptsExactBoundaryAndRejectsFirstExcessByte() {
        val exact = ByteArrayOutputStream()
        assertEquals(4L, copyBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), exact, 4))
        assertEquals(4, exact.size())
        assertThrows(IllegalArgumentException::class.java) {
            copyBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), ByteArrayOutputStream(), 4)
        }
    }
}
