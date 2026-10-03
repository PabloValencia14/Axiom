package org.readera.openreadera

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.scanner.DocumentCategory
import org.readera.openreadera.data.scanner.DocumentCategoryClassifier

class DocumentCategoryOverrideTest {
    @Test
    fun manualChoiceSurvivesRecreationAndMetadataChangesAndCanBeReset() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("override_regression_test_$name", mode)
        }
        val prefs = context.getSharedPreferences(DocumentCategoryClassifier.CACHE_PREFS, Context.MODE_PRIVATE)
        try {
            val book = Book(title = "Tema 1", filePath = "/category-test.pdf", format = "PDF")
            DocumentCategoryClassifier.setManualCategory(context, book, DocumentCategory.BOOK)
            assertEquals(DocumentCategory.BOOK, DocumentCategoryClassifier(context).classify(book).category)
            val edited = book.copy(title = "Diapositivas de redes")
            assertEquals(DocumentCategory.BOOK, DocumentCategoryClassifier(context).classify(edited).category)
            assertEquals(DocumentCategory.BOOK, DocumentCategoryClassifier.cachedOrQuick(context, edited).category)
            DocumentCategoryClassifier.setManualCategory(context, edited, null)
            assertNull(DocumentCategoryClassifier.manualCategory(context, edited))
            assertEquals(DocumentCategory.PRESENTATION, DocumentCategoryClassifier(context).classify(edited).category)
            val key = "manual_category_" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(book.filePath.toByteArray())
                .joinToString("") { "%02x".format(it) }
            prefs.edit().putString(key, "STUDY_DOCUMENT").commit()
            assertEquals(DocumentCategory.DOCUMENT, DocumentCategoryClassifier.manualCategory(context, book))
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
