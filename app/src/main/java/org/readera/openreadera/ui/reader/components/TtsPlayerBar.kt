package org.readera.openreadera.ui.reader.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val TTS_SLEEP_TIMER_CHOICES = listOf(
    null to "Desactivado",
    15 to "15 minutos",
    30 to "30 minutos",
    45 to "45 minutos",
    60 to "60 minutos"
)

@Composable
fun TtsPlayerBar(
    isPlaying: Boolean,
    currentText: String,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    sleepTimerMinutes: Int?,
    onSelectSleepTimer: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = currentText.ifBlank { "Leyendo página en voz alta…" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar TTS")
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Anterior")
                    }

                    FilledIconButton(onClick = onTogglePlay) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pausar" else "Reproducir"
                        )
                    }

                    IconButton(onClick = onNext) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Siguiente")
                    }
                }

                Box(contentAlignment = Alignment.CenterEnd) {
                    var showSleepTimerMenu by remember { mutableStateOf(false) }

                    if (sleepTimerMinutes != null) {
                        FilledTonalButton(
                            onClick = { showSleepTimerMenu = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = "Temporizador de apagado",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${sleepTimerMinutes}m",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { showSleepTimerMenu = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = "Temporizador de apagado: Desactivado. Elegir duración",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showSleepTimerMenu,
                        onDismissRequest = { showSleepTimerMenu = false }
                    ) {
                        Text(
                            text = "Temporizador de apagado",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        HorizontalDivider()
                        TTS_SLEEP_TIMER_CHOICES.forEach { (minutes, label) ->
                            val isSelected = sleepTimerMinutes == minutes
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (minutes == null) Icons.Default.TimerOff else Icons.Default.Timer,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                text = {
                                    Text(
                                        text = label,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                trailingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Seleccionado",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else null,
                                onClick = {
                                    onSelectSleepTimer(minutes)
                                    showSleepTimerMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
