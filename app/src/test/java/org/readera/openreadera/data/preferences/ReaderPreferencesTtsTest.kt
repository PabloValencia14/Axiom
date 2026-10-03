package org.readera.openreadera.data.preferences

import android.content.Context

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReaderPreferencesTtsTest {

    @Test
    fun speechRatePersistsAndClampsAtBothBounds() {
        val context = RuntimeEnvironment.getApplication()
        val sharedPreferences = context.getSharedPreferences("openreadera_prefs", Context.MODE_PRIVATE)
        val hadOldValue = sharedPreferences.contains("tts_rate")
        val oldValue = sharedPreferences.getFloat("tts_rate", 1f)
        try {
            ReaderPreferences(context).updateTtsSpeechRate(-4f)
            assertEquals(0.5f, ReaderPreferences(context).ttsSpeechRate.value)
            ReaderPreferences(context).updateTtsSpeechRate(5f)
            assertEquals(2f, ReaderPreferences(context).ttsSpeechRate.value)
            ReaderPreferences(context).updateTtsSpeechRate(1.25f)
            assertEquals(1.25f, ReaderPreferences(context).ttsSpeechRate.value)
        } finally {
            val editor = sharedPreferences.edit()
            if (hadOldValue) editor.putFloat("tts_rate", oldValue) else editor.remove("tts_rate")
            editor.commit()
        }
    }

    @Test
    fun onlineNeuralTtsDefaultsOffAndPersistsExplicitChoice() {
        val context = RuntimeEnvironment.getApplication()
        val sharedPreferences = context.getSharedPreferences("openreadera_prefs", Context.MODE_PRIVATE)
        val hadOldValue = sharedPreferences.contains("use_neural_tts_online")
        val oldValue = sharedPreferences.getBoolean("use_neural_tts_online", false)
        try {
            sharedPreferences.edit().remove("use_neural_tts_online").commit()
            assertEquals(false, ReaderPreferences(context).useNeuralTtsOnline.value)

            ReaderPreferences(context).updateUseNeuralTtsOnline(true)
            val recreatedPreferences = ReaderPreferences(context)
            assertEquals(true, recreatedPreferences.useNeuralTtsOnline.value)
        } finally {
            val editor = sharedPreferences.edit()
            if (hadOldValue) editor.putBoolean("use_neural_tts_online", oldValue)
            else editor.remove("use_neural_tts_online")
            editor.commit()
        }
    }
}
