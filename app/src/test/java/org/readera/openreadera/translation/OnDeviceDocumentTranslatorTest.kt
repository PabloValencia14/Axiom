package org.readera.openreadera.translation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OnDeviceDocumentTranslatorTest {
    @Test fun equalLanguagesNeedNoClientOrDownloadAndPreserveEveryCharacter() = runTest {
        OnDeviceDocumentTranslator("es", "es").use { translator ->
            translator.prepare()
            val text = "Fórmula: E = mc²\n\nSegundo párrafo."
            assertEquals(text, translator.translateText(text).getOrThrow())
            assertEquals("", translator.translateText("").getOrThrow())
        }
    }

    @Test fun unknownLanguageIsRejectedBeforeModelCreation() {
        assertThrows(IllegalArgumentException::class.java) { OnDeviceDocumentTranslator("invalid", "es") }
    }
}
