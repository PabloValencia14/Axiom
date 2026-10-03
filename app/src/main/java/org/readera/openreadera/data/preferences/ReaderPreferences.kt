package org.readera.openreadera.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.readera.openreadera.data.model.AppThemeMode
import org.readera.openreadera.data.model.ReaderColorTheme
import org.readera.openreadera.data.model.ReaderSettings
import org.readera.openreadera.data.model.ReaderViewMode

class ReaderPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("openreadera_prefs", Context.MODE_PRIVATE)

    // Global App Theme Mode (System / Light / Dark / OLED)
    private val _appThemeMode = MutableStateFlow(loadAppThemeMode())
    val appThemeMode: StateFlow<AppThemeMode> = _appThemeMode.asStateFlow()

    // Dynamic Color (Material You wallpaper accent)
    private val _dynamicColor = MutableStateFlow(prefs.getBoolean("dynamic_color", false))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    // Library View Mode (Grid vs Compact List)
    private val _libraryCompactMode = MutableStateFlow(prefs.getBoolean("library_compact_mode", false))
    val libraryCompactMode: StateFlow<Boolean> = _libraryCompactMode.asStateFlow()

    fun updateLibraryCompactMode(compact: Boolean) {
        prefs.edit().putBoolean("library_compact_mode", compact).apply()
        _libraryCompactMode.value = compact
    }

    private val _useNeuralTtsOnline = MutableStateFlow(prefs.getBoolean("use_neural_tts_online", false))
    val useNeuralTtsOnline: StateFlow<Boolean> = _useNeuralTtsOnline.asStateFlow()

    fun updateUseNeuralTtsOnline(enabled: Boolean) {
        prefs.edit().putBoolean("use_neural_tts_online", enabled).apply()
        _useNeuralTtsOnline.value = enabled
    }

    private val _ttsSpeechRate = MutableStateFlow(
        prefs.getFloat("tts_rate", 1.0f).let { if (it.isFinite()) it.coerceIn(0.5f, 2.0f) else 1.0f }
    )
    val ttsSpeechRate: StateFlow<Float> = _ttsSpeechRate.asStateFlow()

    fun updateTtsSpeechRate(rate: Float) {
        val clampedRate = if (rate.isFinite()) rate.coerceIn(0.5f, 2.0f) else 1.0f
        prefs.edit().putFloat("tts_rate", clampedRate).apply()
        _ttsSpeechRate.value = clampedRate
    }

    private fun loadAppThemeMode(): AppThemeMode {
        val name = prefs.getString("app_theme_mode", AppThemeMode.SYSTEM.name) ?: AppThemeMode.SYSTEM.name
        return try {
            AppThemeMode.valueOf(name)
        } catch (_: Exception) {
            AppThemeMode.SYSTEM
        }
    }

    fun updateAppThemeMode(mode: AppThemeMode) {
        prefs.edit().putString("app_theme_mode", mode.name).apply()
        _appThemeMode.value = mode
    }

    fun updateDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
        _dynamicColor.value = enabled
    }

    fun drawingToolName(): String = prefs.getString("drawing_tool", "PEN") ?: "PEN"
    fun drawingColorHex(): String = prefs.getString("drawing_color", "#FACC15") ?: "#FACC15"
    fun drawingStrokeWidth(): Float = prefs.getFloat("drawing_stroke_width", 4f)

    fun updateDrawingToolName(name: String) {
        prefs.edit().putString("drawing_tool", name).apply()
    }

    fun updateDrawingColorHex(colorHex: String) {
        prefs.edit().putString("drawing_color", colorHex).apply()
    }

    fun updateDrawingStrokeWidth(width: Float) {
        prefs.edit().putFloat("drawing_stroke_width", width.coerceIn(1f, 32f)).apply()
    }


    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    private fun loadSettings(): ReaderSettings {
        val themeName = prefs.getString("theme", ReaderColorTheme.SYSTEM.name) ?: ReaderColorTheme.SYSTEM.name
        val modeName = prefs.getString("view_mode", ReaderViewMode.PAGED.name) ?: ReaderViewMode.PAGED.name

        return ReaderSettings(
            theme = try { ReaderColorTheme.valueOf(themeName) } catch (_: Exception) { ReaderColorTheme.SYSTEM },
            viewMode = try { ReaderViewMode.valueOf(modeName) } catch (_: Exception) { ReaderViewMode.PAGED },
            fontSizeSp = prefs.getInt("font_size", 16),
            lineSpacing = prefs.getFloat("line_spacing", 1.2f),
            marginHorizontalDp = prefs.getInt("margin_horizontal", 16),
            fontFamily = normalizeFontFamily(prefs.getString("font_family", "SansSerif")),
            brightness = prefs.getFloat("brightness", -1f),
            keepScreenOn = prefs.getBoolean("keep_screen_on", true),
            invertImagesInDark = prefs.getBoolean("invert_images", false),
            volumeKeysTurnPages = prefs.getBoolean("volume_keys", true),
            bionicReading = prefs.getBoolean("bionic_reading", false),
            fontBold = prefs.getBoolean("font_bold", false)
        )
    }

    private fun normalizeFontFamily(family: String?): String =
        when (family) {
            "Serif" -> "Serif"
            "Monospace" -> "Monospace"
            else -> "SansSerif"
        }

    fun updateTheme(theme: ReaderColorTheme) {
        prefs.edit().putString("theme", theme.name).apply()
        _settings.value = _settings.value.copy(theme = theme)
    }

    fun updateViewMode(viewMode: ReaderViewMode) {
        prefs.edit().putString("view_mode", viewMode.name).apply()
        _settings.value = _settings.value.copy(viewMode = viewMode)
    }

    fun updateFontSize(sizeSp: Int) {
        val clamped = sizeSp.coerceIn(10, 36)
        prefs.edit().putInt("font_size", clamped).apply()
        _settings.value = _settings.value.copy(fontSizeSp = clamped)
    }

    fun updateLineSpacing(spacing: Float) {
        val clamped = spacing.coerceIn(0.8f, 2.5f)
        prefs.edit().putFloat("line_spacing", clamped).apply()
        _settings.value = _settings.value.copy(lineSpacing = clamped)
    }

    fun updateMargin(marginDp: Int) {
        val clamped = marginDp.coerceIn(0, 48)
        prefs.edit().putInt("margin_horizontal", clamped).apply()
        _settings.value = _settings.value.copy(marginHorizontalDp = clamped)
    }

    fun updateFontFamily(family: String) {
        val supportedFamily = normalizeFontFamily(family)
        prefs.edit().putString("font_family", supportedFamily).apply()
        _settings.value = _settings.value.copy(fontFamily = supportedFamily)
    }


    fun updateBrightness(brightness: Float) {
        prefs.edit().putFloat("brightness", brightness).apply()
        _settings.value = _settings.value.copy(brightness = brightness)
    }

    fun updateBionicReading(enabled: Boolean) {
        prefs.edit().putBoolean("bionic_reading", enabled).apply()
        _settings.value = _settings.value.copy(bionicReading = enabled)
    }

    fun updateFontBold(enabled: Boolean) {
        prefs.edit().putBoolean("font_bold", enabled).apply()
        _settings.value = _settings.value.copy(fontBold = enabled)
    }
    fun refreshAfterSync() {
        _appThemeMode.value = loadAppThemeMode()
        _dynamicColor.value = prefs.getBoolean("dynamic_color", false)
        _useNeuralTtsOnline.value = prefs.getBoolean("use_neural_tts_online", false)
        _ttsSpeechRate.value = prefs.getFloat("tts_rate", 1.0f)
            .let { if (it.isFinite()) it.coerceIn(0.5f, 2.0f) else 1.0f }
        _settings.value = loadSettings()
        _libraryCompactMode.value = prefs.getBoolean("library_compact_mode", false)
    }

}
