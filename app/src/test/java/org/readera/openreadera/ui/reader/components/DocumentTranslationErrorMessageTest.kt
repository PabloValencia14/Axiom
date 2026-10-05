package org.readera.openreadera.ui.reader.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readera.openreadera.engine.DocumentFormat

class DocumentTranslationErrorMessageTest {
    @Test fun pdfFidelityCopyExplainsBandFlowAndNativeTextLimits() {
        val message = nativeTranslationFidelityInfo(DocumentFormat.PDF, "pdf")
        assertTrue(message.contains("debajo"))
        assertTrue(message.contains("bandas"))
        assertTrue(message.contains("sin reducir la fuente"))
        assertTrue(message.contains("no se reconstruyen escaneos"))
    }

    @Test fun unrelatedProviderFailuresKeepTheirMessage() {
        assertEquals("Network unavailable", documentTranslationErrorMessage(IllegalStateException("Network unavailable")))
    }
}
