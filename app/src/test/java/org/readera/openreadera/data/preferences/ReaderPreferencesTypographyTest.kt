package org.readera.openreadera.data.preferences

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReaderPreferencesTypographyTest {
    @Test
    fun boldFontSettingDefaultsOffAndPersists() {
        val context = RuntimeEnvironment.getApplication()
        val sharedPreferences = context.getSharedPreferences("openreadera_prefs", Context.MODE_PRIVATE)
        val hadOldValue = sharedPreferences.contains("font_bold")
        val oldValue = sharedPreferences.getBoolean("font_bold", false)
        try {
            sharedPreferences.edit().remove("font_bold").commit()
            assertFalse(ReaderPreferences(context).settings.value.fontBold)

            val preferences = ReaderPreferences(context)
            preferences.updateFontBold(true)
            assertTrue(preferences.settings.value.fontBold)
            assertTrue(ReaderPreferences(context).settings.value.fontBold)
        } finally {
            if (hadOldValue) sharedPreferences.edit().putBoolean("font_bold", oldValue).commit()
            else sharedPreferences.edit().remove("font_bold").commit()
        }
    }

    @Test
    fun unsupportedFontFamilyFallsBackToSansSerif() {
        val context = RuntimeEnvironment.getApplication()
        val sharedPreferences = context.getSharedPreferences("openreadera_prefs", Context.MODE_PRIVATE)
        val hadOldValue = sharedPreferences.contains("font_family")
        val oldValue = sharedPreferences.getString("font_family", null)
        try {
            sharedPreferences.edit().putString("font_family", "Merriweather").commit()
            assertEquals("SansSerif", ReaderPreferences(context).settings.value.fontFamily)

            val preferences = ReaderPreferences(context)
            preferences.updateFontFamily("Merriweather")
            assertEquals("SansSerif", preferences.settings.value.fontFamily)
            assertEquals("SansSerif", ReaderPreferences(context).settings.value.fontFamily)
        } finally {
            if (hadOldValue) sharedPreferences.edit().putString("font_family", oldValue).commit()
            else sharedPreferences.edit().remove("font_family").commit()
        }
    }
}
