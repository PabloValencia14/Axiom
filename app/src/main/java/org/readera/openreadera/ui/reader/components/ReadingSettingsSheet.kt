package org.readera.openreadera.ui.reader.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.readera.openreadera.data.model.ReaderColorTheme
import org.readera.openreadera.data.model.ReaderSettings
import org.readera.openreadera.data.model.ReaderViewMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingSettingsSheet(
    settings: ReaderSettings,
    isPdf: Boolean,
    zoomScale: Float = 1.0f,
    onZoomChange: (Float) -> Unit = {},
    onDismiss: () -> Unit,
    onThemeChange: (ReaderColorTheme) -> Unit,
    onViewModeChange: (ReaderViewMode) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onMarginChange: (Int) -> Unit,
    onFontFamilyChange: (String) -> Unit,
    onToggleFontBold: (Boolean) -> Unit,
    onToggleBionicReading: () -> Unit = {},
    onOpenGeneralSettings: () -> Unit
) {

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = if (isPdf) "Configuración: Documento fijo (PDF)" else "Configuración: Texto refluible (EPUB)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Formas de voltear la página
            Text(text = "Formas de voltear la página", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = settings.viewMode != ReaderViewMode.VERTICAL_SCROLL,
                    onClick = { onViewModeChange(if (settings.viewMode == ReaderViewMode.DOUBLE_PAGE) ReaderViewMode.DOUBLE_PAGE else ReaderViewMode.PAGED) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("Horizontal")
                }
                SegmentedButton(
                    selected = settings.viewMode == ReaderViewMode.VERTICAL_SCROLL,
                    onClick = { onViewModeChange(ReaderViewMode.VERTICAL_SCROLL) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("Vertical")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Dos páginas con orientación horizontal
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Dos páginas con orientación horizontal", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text("Muestra dos páginas lado a lado en pantallas anchas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.viewMode == ReaderViewMode.DOUBLE_PAGE,
                    onCheckedChange = { checked ->
                        onViewModeChange(if (checked) ReaderViewMode.DOUBLE_PAGE else ReaderViewMode.PAGED)
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Modos de color
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Modos de color", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (settings.theme == ReaderColorTheme.SYSTEM) {
                    Text(
                        text = "Sincronizado con Android",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    ReaderColorTheme.SYSTEM to "Sistema",
                    ReaderColorTheme.DAY to "Día",
                    ReaderColorTheme.SEPIA to "Sepia",
                    ReaderColorTheme.NIGHT to "Noche",
                    ReaderColorTheme.OLED to "OLED"
                ).forEach { (theme, label) ->
                    FilterChip(
                        selected = settings.theme == theme,
                        onClick = { onThemeChange(theme) },
                        label = { Text(label, maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!isPdf) {
                // Perfil EPUB / FB2 / MOBI / DOC / DOCX / RTF / TXT / CHM
                Text(text = "Tipo de fuente", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("SansSerif" to "Sans", "Serif" to "Serif", "Monospace" to "Mono").forEach { (font, label) ->
                        FilterChip(
                            selected = settings.fontFamily == font,
                            onClick = { onFontFamilyChange(font) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Tamaño de fuente
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Tamaño de fuente", style = MaterialTheme.typography.bodyLarge)
                    Text(text = "${settings.fontSizeSp}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = settings.fontSizeSp.toFloat(),
                    onValueChange = { onFontSizeChange(it.toInt()) },
                    valueRange = 14f..48f,
                    steps = 17
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Grosor de fuente (Negrita)", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = settings.fontBold, onCheckedChange = onToggleFontBold)
                }
                Spacer(modifier = Modifier.height(12.dp))

                // Interlineado
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Interlineado", style = MaterialTheme.typography.bodyLarge)
                    Text(text = "${(settings.lineSpacing * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = settings.lineSpacing,
                    onValueChange = onLineSpacingChange,
                    valueRange = 0.9f..2.0f,
                    steps = 11
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Márgenes de página", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = settings.marginHorizontalDp.toFloat(),
                    onValueChange = { onMarginChange(it.toInt()) },
                    valueRange = 4f..48f,
                    steps = 11
                )
            }

            Spacer(modifier = Modifier.height(12.dp))


            if (!isPdf) {
                Spacer(modifier = Modifier.height(16.dp))

                // Lectura Biónica
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Lectura Biónica", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "Resalta las primeras letras de cada palabra para acelerar la comprensión visual",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.bionicReading,
                        onCheckedChange = { onToggleBionicReading() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Escala y Zoom de Lectura
            Text(text = "Escala de Zoom", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Nivel de aumento", style = MaterialTheme.typography.bodyLarge)
                Text(text = "${(zoomScale * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = zoomScale,
                onValueChange = { onZoomChange(it) },
                valueRange = 1.0f..4.0f,
                steps = 11
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(1.0f, 1.5f, 2.0f, 2.5f).forEach { preset ->
                    OutlinedButton(
                        onClick = { onZoomChange(preset) },
                        modifier = Modifier.weight(1f),
                        colors = if (kotlin.math.abs(zoomScale - preset) < 0.05f) {
                            ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        } else ButtonDefaults.outlinedButtonColors(),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Text("${(preset * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Botón CONFIGURACIÓN GENERAL
            OutlinedButton(
                onClick = {
                    onDismiss()
                    onOpenGeneralSettings()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("CONFIGURACIÓN GENERAL", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
