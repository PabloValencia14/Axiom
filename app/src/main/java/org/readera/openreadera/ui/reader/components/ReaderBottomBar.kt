package org.readera.openreadera.ui.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import org.readera.openreadera.data.model.ReaderColorTheme
import org.readera.openreadera.ui.theme.NightBackground
import org.readera.openreadera.ui.theme.OledBackground
import org.readera.openreadera.ui.theme.SepiaBackground

@Composable
fun ReaderBottomBar(
    currentPage: Int,
    totalPages: Int,
    currentTheme: ReaderColorTheme,
    fontSize: Int,
    brightness: Float,
    isPdf: Boolean,
    movementLocked: Boolean,
    onPageChange: (Int) -> Unit,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit,
    onThemeChange: (ReaderColorTheme) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onToggleOrientation: () -> Unit,
    onToggleMovementLock: () -> Unit,
    onAddToHistory: () -> Unit,
    estimatedMinutesRemaining: Int = 0,
    isOled: Boolean = false,
    readingAnchorPage: Int? = null,
    onReturnToAnchor: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showGoToPageDialog by remember { mutableStateOf(false) }
    var showBrightnessSlider by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (isOled) Color(0xFF000000) else MaterialTheme.colorScheme.surface,
        tonalElevation = if (isOled) 0.dp else 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Brightness slider popover
            if (showBrightnessSlider) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.BrightnessLow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Slider(
                        value = brightness,
                        onValueChange = onBrightnessChange,
                        valueRange = 0.1f..1.0f,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.Default.BrightnessHigh, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }


            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )

            // Page seek slider & navigation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPreviousPage, enabled = currentPage > 0) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Página anterior"
                    )
                }

                var isDragging by remember { mutableStateOf(false) }
                var dragValue by remember { mutableFloatStateOf(currentPage.toFloat()) }

                LaunchedEffect(currentPage) {
                    if (!isDragging) {
                        dragValue = currentPage.toFloat()
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val displayPage = if (isDragging) dragValue.toInt() else currentPage
                    val percent = if (totalPages > 0) ((displayPage + 1).toFloat() / totalPages * 100).toInt() else 0
                    val timeText = if (estimatedMinutesRemaining > 0) " • ~$estimatedMinutesRemaining min restantes" else ""

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "${displayPage + 1} / $totalPages  ($percent%)$timeText",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clickable { showGoToPageDialog = true }
                        )

                        if (readingAnchorPage != null && readingAnchorPage != currentPage && onReturnToAnchor != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.clickable { onReturnToAnchor() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BookmarkBorder,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Volver a pág. ${readingAnchorPage + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    Slider(
                        value = if (isDragging) dragValue else currentPage.toFloat(),
                        onValueChange = {
                            isDragging = true
                            dragValue = it
                        },
                        onValueChangeFinished = {
                            isDragging = false
                            onPageChange(dragValue.toInt())
                        },
                        valueRange = 0f..maxOf(1, totalPages - 1).toFloat(),
                        steps = if (totalPages > 2) (totalPages - 2).coerceAtMost(100) else 0,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                IconButton(onClick = onNextPage, enabled = currentPage < totalPages - 1) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Página siguiente"
                    )
                }
            }

            // Bottom controls: Brillo, Ir a la página, Orientación, Historial, Bloquear movimiento (PDF) + Temas
            val isPhone = LocalConfiguration.current.screenWidthDp < 600

            if (isPhone) {
                // Mobile: Row 1 - Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showBrightnessSlider = !showBrightnessSlider }) {
                        Icon(Icons.Default.Brightness6, contentDescription = "Control de brillo")
                    }

                    IconButton(onClick = { showGoToPageDialog = true }) {
                        Icon(Icons.Default.Pin, contentDescription = "Ir a la página")
                    }

                    IconButton(onClick = onToggleOrientation) {
                        Icon(Icons.Default.ScreenRotation, contentDescription = "Orientación de pantalla")
                    }

                    IconButton(onClick = onAddToHistory) {
                        Icon(Icons.Default.History, contentDescription = "Añadir a Leyendo ahora")
                    }

                    if (isPdf) {
                        IconButton(onClick = onToggleMovementLock) {
                            Icon(
                                imageVector = if (movementLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = "Bloquear movimiento",
                                tint = if (movementLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Mobile: Row 2 - Theme Pills
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(
                                    if (currentTheme == ReaderColorTheme.SYSTEM)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    width = if (currentTheme == ReaderColorTheme.SYSTEM) 2.dp else 1.dp,
                                    color = if (currentTheme == ReaderColorTheme.SYSTEM)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape
                                )
                                .clickable { onThemeChange(ReaderColorTheme.SYSTEM) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.BrightnessAuto,
                                contentDescription = "Sistema (Sincronizado)",
                                modifier = Modifier.size(16.dp),
                                tint = if (currentTheme == ReaderColorTheme.SYSTEM)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ThemeCircle(
                            color = Color(0xFFFFFFFF),
                            borderColor = Color(0xFFCCCCCC),
                            isSelected = currentTheme == ReaderColorTheme.DAY,
                            onClick = { onThemeChange(ReaderColorTheme.DAY) }
                        )
                        ThemeCircle(
                            color = SepiaBackground,
                            borderColor = Color(0xFFBCAAA4),
                            isSelected = currentTheme == ReaderColorTheme.SEPIA,
                            onClick = { onThemeChange(ReaderColorTheme.SEPIA) }
                        )
                        ThemeCircle(
                            color = NightBackground,
                            borderColor = Color(0xFF555555),
                            isSelected = currentTheme == ReaderColorTheme.NIGHT,
                            onClick = { onThemeChange(ReaderColorTheme.NIGHT) }
                        )
                        ThemeCircle(
                            color = OledBackground,
                            borderColor = if (currentTheme == ReaderColorTheme.OLED) Color.White else Color(0xFF444444),
                            isSelected = currentTheme == ReaderColorTheme.OLED,
                            onClick = { onThemeChange(ReaderColorTheme.OLED) }
                        )
                    }
                }
            } else {
                // Tablet / Desktop: Single Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showBrightnessSlider = !showBrightnessSlider }) {
                        Icon(Icons.Default.Brightness6, contentDescription = "Control de brillo")
                    }

                    TextButton(onClick = { showGoToPageDialog = true }) {
                        Icon(Icons.Default.Pin, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Ir a la página")
                    }

                    IconButton(onClick = onToggleOrientation) {
                        Icon(Icons.Default.ScreenRotation, contentDescription = "Orientación de pantalla")
                    }

                    IconButton(onClick = onAddToHistory) {
                        Icon(Icons.Default.History, contentDescription = "Añadir a Leyendo ahora")
                    }

                    if (isPdf) {
                        IconButton(onClick = onToggleMovementLock) {
                            Icon(
                                imageVector = if (movementLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = "Bloquear movimiento",
                                tint = if (movementLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(
                                    if (currentTheme == ReaderColorTheme.SYSTEM)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    width = if (currentTheme == ReaderColorTheme.SYSTEM) 2.dp else 1.dp,
                                    color = if (currentTheme == ReaderColorTheme.SYSTEM)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape
                                )
                                .clickable { onThemeChange(ReaderColorTheme.SYSTEM) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.BrightnessAuto,
                                contentDescription = "Sistema (Sincronizado)",
                                modifier = Modifier.size(16.dp),
                                tint = if (currentTheme == ReaderColorTheme.SYSTEM)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ThemeCircle(
                            color = Color(0xFFFFFFFF),
                            borderColor = Color(0xFFCCCCCC),
                            isSelected = currentTheme == ReaderColorTheme.DAY,
                            onClick = { onThemeChange(ReaderColorTheme.DAY) }
                        )
                        ThemeCircle(
                            color = SepiaBackground,
                            borderColor = Color(0xFFBCAAA4),
                            isSelected = currentTheme == ReaderColorTheme.SEPIA,
                            onClick = { onThemeChange(ReaderColorTheme.SEPIA) }
                        )
                        ThemeCircle(
                            color = NightBackground,
                            borderColor = Color(0xFF555555),
                            isSelected = currentTheme == ReaderColorTheme.NIGHT,
                            onClick = { onThemeChange(ReaderColorTheme.NIGHT) }
                        )
                        ThemeCircle(
                            color = OledBackground,
                            borderColor = if (currentTheme == ReaderColorTheme.OLED) Color.White else Color(0xFF444444),
                            isSelected = currentTheme == ReaderColorTheme.OLED,
                            onClick = { onThemeChange(ReaderColorTheme.OLED) }
                        )
                    }
                }
            }
        }
    }

    // Ir a la página Dialog
    if (showGoToPageDialog) {
        var pageText by remember { mutableStateOf((currentPage + 1).toString()) }
        AlertDialog(
            onDismissRequest = { showGoToPageDialog = false },
            title = { Text("Ir a la página", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Ingrese un número de página (1 a $totalPages):")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pageText,
                        onValueChange = { pageText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showGoToPageDialog = false }) {
                    Text("CANCELAR")
                }
            },
            confirmButton = {
                Button(onClick = {
                    val p = pageText.toIntOrNull()
                    if (p != null && p in 1..totalPages) {
                        onPageChange(p - 1)
                        showGoToPageDialog = false
                    }
                }) {
                    Text("IR")
                }
            }
        )
    }
}

@Composable
fun ThemeCircle(
    color: Color,
    borderColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else borderColor,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}
