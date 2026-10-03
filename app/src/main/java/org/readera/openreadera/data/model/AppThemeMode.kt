package org.readera.openreadera.data.model

enum class AppThemeMode(val title: String, val description: String) {
    SYSTEM(
        title = "Sincronizado con el sistema",
        description = "Sigue automáticamente el tema claro u oscuro de tu dispositivo"
    ),
    LIGHT(
        title = "Modo Claro",
        description = "Fondo marfil claro y tonos cálidos para lectura diurna"
    ),
    DARK(
        title = "Modo Oscuro",
        description = "Fondo carbón suave y descansado para ambientes oscuros"
    ),
    OLED(
        title = "Negro Puro (OLED)",
        description = "Fondo negro absoluto (0% emisión) para máximo ahorro de batería"
    )
}
