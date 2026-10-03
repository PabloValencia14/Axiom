package org.readera.openreadera.ui.reader.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextHighlightSelectionBar(
    isVisible: Boolean,
    selectedText: String,
    bookTitle: String,
    bookAuthor: String,
    page: Int,
    onHighlightColor: (colorHex: String) -> Unit,
    onAddNote: () -> Unit,
    onPlayTts: (String) -> Unit,
    onCreateQuoteCard: () -> Unit,
    onShowDictionary: ((String) -> Unit)? = null,
    onTranslate: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    AnimatedVisibility(
        visible = isVisible && selectedText.isNotBlank(),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .wrapContentWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. Color swatches for 1-tap highlighting
                val highlightColors = listOf(
                    "#FEF08A" to "Amarillo (Ideas)",
                    "#BBF7D0" to "Verde (Citas)",
                    "#BAE6FD" to "Azul (Dudas)",
                    "#FBCFE8" to "Rosa (Crítica)",
                    "#E9D5FF" to "Lila (Vocabulario)"
                )

                highlightColors.forEach { (hex, label) ->
                    val color = Color(android.graphics.Color.parseColor(hex))
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(label) } },
                        state = rememberTooltipState()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                                .clickable { onHighlightColor(hex) }
                        )
                    }
                }

                Divider(
                    modifier = Modifier
                        .height(24.dp)
                        .width(1.dp)
                )

                // 2. Add Note button
                IconButton(
                    onClick = onAddNote,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.EditNote,
                        contentDescription = "Añadir nota al margen",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // 3. Copy with formal citation
                IconButton(
                    onClick = {
                        val citation = "«${selectedText.trim()}»\n— $bookAuthor, $bookTitle (Pág. ${page + 1})"
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Cita de Axiom", citation)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Cita copiada al portapapeles", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copiar cita bibliográfica",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 4. TTS speech
                IconButton(
                    onClick = { onPlayTts(selectedText) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = "Escuchar fragmento",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 5. Enciclopedia / Diccionario
                if (onShowDictionary != null) {
                    IconButton(
                        onClick = { onShowDictionary(selectedText) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.MenuBook,
                            contentDescription = "Buscar definición / Wikipedia",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 6. Traducir fragmento
                if (onTranslate != null) {
                    IconButton(
                        onClick = { onTranslate(selectedText) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Translate,
                            contentDescription = "Traducir texto",
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                // 7. Quote Card Maker
                IconButton(
                    onClick = onCreateQuoteCard,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.PhotoLibrary,
                        contentDescription = "Crear tarjeta de cita",
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }

                // 8. Dismiss
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cerrar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
