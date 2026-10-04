package org.readera.openreadera.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readera.openreadera.ui.library.components.DocumentFormatCapabilities

class DocumentFormatCapabilitiesTest {

    @Test
    fun distinguishesReadableFromIndexedFormats() {
        val readableSample = listOf("pdf", "epub", "cbz", "txt", "docx")
        for (fmt in readableSample) {
            assertTrue("Expected $fmt to be readable", DocumentFormatCapabilities.isReadable(fmt))
            assertFalse("Expected $fmt not to be indexed-only", DocumentFormatCapabilities.isIndexedOnly(fmt))
        }

        val indexedSample = listOf("djvu", "cbr", "rtf", "doc", "chm")
        for (fmt in indexedSample) {
            assertFalse("Expected $fmt not to be readable directly", DocumentFormatCapabilities.isReadable(fmt))
            assertTrue("Expected $fmt to be indexed-only", DocumentFormatCapabilities.isIndexedOnly(fmt))
        }
    }

    @Test
    fun handlesCaseInsensitivityAndLeadingDots() {
        assertTrue(DocumentFormatCapabilities.isReadable("PDF"))
        assertTrue(DocumentFormatCapabilities.isReadable(".EPUB"))
        assertTrue(DocumentFormatCapabilities.isReadable("Docx"))
        assertTrue(DocumentFormatCapabilities.isReadable(".cbz"))

        assertTrue(DocumentFormatCapabilities.isIndexedOnly("DJVU"))
        assertTrue(DocumentFormatCapabilities.isIndexedOnly(".RTF"))
        assertTrue(DocumentFormatCapabilities.isIndexedOnly("Cbr"))
        assertTrue(DocumentFormatCapabilities.isIndexedOnly(".doc"))
    }

    @Test
    fun handlesNullAndBlankSafely() {
        assertFalse(DocumentFormatCapabilities.isReadable(null))
        assertFalse(DocumentFormatCapabilities.isReadable(""))
        assertFalse(DocumentFormatCapabilities.isReadable("   "))

        assertFalse(DocumentFormatCapabilities.isIndexedOnly(null))
        assertFalse(DocumentFormatCapabilities.isIndexedOnly(""))
        assertFalse(DocumentFormatCapabilities.isIndexedOnly("   "))
    }
}
