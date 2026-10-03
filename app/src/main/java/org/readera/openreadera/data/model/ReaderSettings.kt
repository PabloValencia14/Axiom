package org.readera.openreadera.data.model

enum class ReaderColorTheme(val displayName: String) {
    SYSTEM("Sistema"),
    DAY("Día"),
    SEPIA("Sepia"),
    NIGHT("Noche"),
    OLED("OLED")
}

enum class ReaderViewMode {
    PAGED,
    DOUBLE_PAGE,
    VERTICAL_SCROLL,
    REFLOW
}

data class ReaderSettings(
    val theme: ReaderColorTheme = ReaderColorTheme.SYSTEM,
    val viewMode: ReaderViewMode = ReaderViewMode.PAGED,
    val fontSizeSp: Int = 16,
    val lineSpacing: Float = 1.2f,
    val marginHorizontalDp: Int = 16,
    val fontFamily: String = "SansSerif",
    val brightness: Float = -1f, // -1f means follow system brightness
    val keepScreenOn: Boolean = true,
    val invertImagesInDark: Boolean = false,
    val volumeKeysTurnPages: Boolean = true,
    val bionicReading: Boolean = false,
    val fontBold: Boolean = false
)
