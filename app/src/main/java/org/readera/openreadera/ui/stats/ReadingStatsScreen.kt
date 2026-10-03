package org.readera.openreadera.ui.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.readera.openreadera.data.stats.ReadingStatsManager
import org.readera.openreadera.data.stats.StatsSummary

@Composable
fun ReadingStatsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val statsManager = remember { ReadingStatsManager(context) }
    var stats by remember { mutableStateOf(statsManager.getStatsSummary()) }


    val isPhone = LocalConfiguration.current.screenWidthDp < 600

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (isPhone) 14.dp else 24.dp, vertical = 16.dp)
    ) {
        // Top Heading
        Text(
            text = "Estadísticas y Hábitos de Lectura",
            style = if (isPhone) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Seguimiento diario de tu constancia y progreso como lector",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 1. Streak Hero Card
        StreakHeroCard(
            streak = stats.currentStreakDays,
            bestStreak = stats.bestStreakDays
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 2. Metrics Card Matrix (2x2 on phone, 1x4 on tablet)
        if (isPhone) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Tiempo hoy",
                        value = "${stats.todayMinutes} min",
                        subtext = "Meta diaria: 30 min",
                        icon = Icons.Default.AccessTime,
                        iconColor = MaterialTheme.colorScheme.primary
                    )
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Total leído",
                        value = String.format("%.1fh", stats.totalHours),
                        subtext = "En todos tus libros",
                        icon = Icons.Default.HourglassBottom,
                        iconColor = MaterialTheme.colorScheme.secondary
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Páginas leídas",
                        value = "${stats.totalPagesRead}",
                        subtext = "Páginas pasadas",
                        icon = Icons.Default.MenuBook,
                        iconColor = MaterialTheme.colorScheme.tertiary
                    )
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Libros terminados",
                        value = "${stats.completedBooks}",
                        subtext = "Completados al 100%",
                        icon = Icons.Default.EmojiEvents,
                        iconColor = Color(0xFFF59E0B)
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Tiempo hoy",
                    value = "${stats.todayMinutes} min",
                    subtext = "Meta diaria: 30 min",
                    icon = Icons.Default.AccessTime,
                    iconColor = MaterialTheme.colorScheme.primary
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Total leído",
                    value = String.format("%.1fh", stats.totalHours),
                    subtext = "En todos tus libros",
                    icon = Icons.Default.HourglassBottom,
                    iconColor = MaterialTheme.colorScheme.secondary
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Páginas leídas",
                    value = "${stats.totalPagesRead}",
                    subtext = "Páginas pasadas",
                    icon = Icons.Default.MenuBook,
                    iconColor = MaterialTheme.colorScheme.tertiary
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Libros terminados",
                    value = "${stats.completedBooks}",
                    subtext = "Completados al 100%",
                    icon = Icons.Default.EmojiEvents,
                    iconColor = Color(0xFFF59E0B)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 3. Weekly Activity Chart
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Actividad de los últimos 7 días",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Minutos dedicados a la lectura cada día",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (stats.averageWpm > 0) {
                                    "Velocidad media: ~${stats.averageWpm} ppm"
                                } else {
                                    "Velocidad media: sin datos"
                                }
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                val maxMinutes = maxOf(30, stats.weeklyMinutes.maxOfOrNull { it.second } ?: 30)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    stats.weeklyMinutes.forEach { (day, minutes) ->
                        val targetFraction = (minutes.toFloat() / maxMinutes.toFloat()).coerceIn(0.04f, 1f)
                        val animatedFraction by animateFloatAsState(
                            targetValue = targetFraction,
                            animationSpec = tween(durationMillis = 600),
                            label = "barHeight"
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                            modifier = Modifier.width(56.dp)
                        ) {
                            Text(
                                text = if (minutes > 0) "${minutes}m" else "-",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (minutes > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .width(32.dp)
                                    .fillMaxHeight(animatedFraction)
                                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                    .background(
                                        if (minutes > 0) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = day,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 4. Badges & Milestones
        Text(
            text = "Logros de Lectura",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (isPhone) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BadgeCard(
                    modifier = Modifier.fillMaxWidth(),
                    title = "7 días seguidos",
                    achieved = stats.currentStreakDays >= 7
                )
                BadgeCard(
                    modifier = Modifier.fillMaxWidth(),
                    title = "10 horas de lectura",
                    achieved = stats.totalHours >= 10f
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                BadgeCard(
                    modifier = Modifier.weight(1f),
                    title = "7 días seguidos",
                    achieved = stats.currentStreakDays >= 7
                )
                BadgeCard(
                    modifier = Modifier.weight(1f),
                    title = "10 horas de lectura",
                    achieved = stats.totalHours >= 10f
                )
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
private fun StreakHeroCard(streak: Int, bestStreak: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "Racha actual",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$streak",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Mejor racha: $bestStreak días",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtext: String,
    icon: ImageVector,
    iconColor: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtext,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun BadgeCard(
    modifier: Modifier = Modifier,
    title: String,
    achieved: Boolean
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (achieved) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (achieved) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
            )
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (achieved) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = if (achieved) "Conseguido" else "Pendiente",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (achieved) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
