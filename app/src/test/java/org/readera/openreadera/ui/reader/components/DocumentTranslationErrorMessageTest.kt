package org.readera.openreadera.ui.reader.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readera.openreadera.engine.DocumentFormat
import org.readera.openreadera.translation.TranslationRejectedException

class DocumentTranslationErrorMessageTest {
    @Test fun pdfFidelityCopyExplainsOriginalPositionsAndExplicitOverflow() {
        val message = nativeTranslationFidelityInfo(DocumentFormat.PDF, "pdf")
        assertTrue(message.contains("zona original"))
        assertTrue(message.contains("en su posición"))
        assertTrue(message.contains("no cabe"))
        assertTrue(message.contains("cancela la exportación"))
        assertTrue(message.contains("no se reconstruyen escaneos"))
    }

    @Test fun unrelatedProviderFailuresKeepTheirMessage() {
        assertEquals("Network unavailable", documentTranslationErrorMessage(IllegalStateException("Network unavailable")))
    }

    @Test fun rejectedPdfIncludesTheAffectedLocation() {
        val message = documentTranslationErrorMessage(TranslationRejectedException("No cabe", "page 3/operator 12"))
        assertTrue(message.contains("No cabe"))
        assertTrue(message.contains("page 3/operator 12"))
    }
}
