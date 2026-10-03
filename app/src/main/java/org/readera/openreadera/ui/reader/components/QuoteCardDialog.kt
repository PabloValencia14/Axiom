package org.readera.openreadera.ui.reader.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

enum class CardTheme(
    val title: String,
    val backgroundColor: Color,
    val textColor: Color,
    val accentColor: Color,
    val metaColor: Color
) {
    PAPER(
        "Papel",
        Color(0xFFFFFFFF),
        Color(0xFF202831),
        Color(0xFF4E6071),
        Color(0xFF4E6071)
    ),
    GRAPHITE(
        "Grafito",
        Color(0xFF202831),
        Color(0xFFF0F3F5),
        Color(0xFFCED7DF),
        Color(0xFFCED7DF)
    )
}

@Composable
fun QuoteCardDialog(
    quoteText: String,
    bookTitle: String,
    bookAuthor: String,
    page: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedTheme by remember { mutableStateOf(CardTheme.PAPER) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tarjeta de Cita",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // The Card Itself
                Card(
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(selectedTheme.backgroundColor)
                            .padding(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "“",
                                fontSize = 48.sp,
                                fontFamily = FontFamily.Serif,
                                color = selectedTheme.accentColor,
                                lineHeight = 36.sp
                            )

                            Text(
                                text = quoteText.trim(),
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Serif,
                                fontStyle = FontStyle.Italic,
                                color = selectedTheme.textColor,
                                textAlign = TextAlign.Center,
                                lineHeight = 24.sp,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Divider(
                                color = selectedTheme.accentColor.copy(alpha = 0.3f),
                                thickness = 1.dp,
                                modifier = Modifier.width(60.dp)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = bookTitle,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = selectedTheme.textColor,
                                textAlign = TextAlign.Center
                            )

                            Text(
                                text = "$bookAuthor • Pág. ${page + 1}",
                                fontSize = 12.sp,
                                color = selectedTheme.metaColor,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "Axiom",
                                fontSize = 10.sp,
                                color = selectedTheme.accentColor.copy(alpha = 0.7f),
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Theme selector swatches
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    val swatchShape = RoundedCornerShape(4.dp)
                    CardTheme.values().forEach { theme ->
                        val isSelected = selectedTheme == theme
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { selectedTheme = theme }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(swatchShape)
                                    .background(theme.backgroundColor)
                                    .then(
                                        if (isSelected) Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.primary,
                                            swatchShape
                                        )
                                        else Modifier.border(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant,
                                            swatchShape
                                        )
                                    )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = theme.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (isSelected) {
                                Text(
                                    text = "Seleccionado",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val formatted = "“${quoteText.trim()}”\n\n— $bookAuthor, $bookTitle (Pág. ${page + 1})\n\nLeído en Axiom"
                            clipboard.setPrimaryClip(ClipData.newPlainText("Cita", formatted))
                            Toast.makeText(context, "Cita formateada copiada", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copiar Cita")
                    }

                    Button(
                        onClick = {
                            val sendIntent = android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                putExtra(android.content.Intent.EXTRA_TEXT, "“${quoteText.trim()}”\n\n— $bookAuthor, $bookTitle (Pág. ${page + 1})")
                                type = "text/plain"
                            }
                            context.startActivity(android.content.Intent.createChooser(sendIntent, "Compartir cita"))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Compartir")
                    }
                }
            }
        }
    }
}
