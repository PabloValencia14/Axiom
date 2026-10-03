package org.readera.openreadera.ui.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.readera.openreadera.data.model.Quote

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddQuoteDialog(
    initialText: String = "",
    page: Int = 0,
    quoteToEdit: Quote? = null,
    onDismiss: () -> Unit,
    onSaveQuote: (text: String, note: String, colorHex: String) -> Unit
) {
    var quoteText by remember(initialText, quoteToEdit) {
        mutableStateOf(quoteToEdit?.text ?: initialText)
    }
    var noteText by remember(quoteToEdit) {
        mutableStateOf(quoteToEdit?.note ?: "")
    }

    val highlightColors = listOf(
        "#FEF08A" to "Amarillo (Ideas)",
        "#BBF7D0" to "Verde (Citas)",
        "#BAE6FD" to "Azul (Dudas)",
        "#FBCFE8" to "Rosa (Crítica)",
        "#E9D5FF" to "Lila (Vocabulario)",
        "#FED7AA" to "Naranja (Importante)"
    )

    var selectedColor by remember(quoteToEdit) {
        mutableStateOf(quoteToEdit?.colorHex ?: highlightColors[0].first)
    }

    val isEditing = quoteToEdit != null
    val targetPage = quoteToEdit?.page ?: page

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isEditing) Icons.Default.EditNote else Icons.Default.FormatQuote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isEditing) "Editar cita o nota (Pág. ${targetPage + 1})" else "Añadir cita o nota (Pág. ${targetPage + 1})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = quoteText,
                    onValueChange = { quoteText = it },
                    label = { Text("Texto de la cita / fragmento") },
                    placeholder = { Text("Fragmento del libro o referencia...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 90.dp, max = 140.dp),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Nota o reflexión personal") },
                    placeholder = { Text("Escribe tu anotación o comentario...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 70.dp, max = 110.dp),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Color de resaltado",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    highlightColors.forEach { (hex, label) ->
                        val color = Color(android.graphics.Color.parseColor(hex))
                        val isSelected = selectedColor.equals(hex, ignoreCase = true)
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text(label) } },
                            state = rememberTooltipState()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .then(
                                        if (isSelected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                        else Modifier.border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape)
                                    )
                                    .clickable { selectedColor = hex }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val canSave = quoteText.isNotBlank() || noteText.isNotBlank()
            Button(
                onClick = {
                    val finalQuote = if (quoteText.isNotBlank()) quoteText.trim() else "Nota al margen"
                    val finalNote = noteText.trim()
                    onSaveQuote(finalQuote, finalNote, selectedColor)
                    onDismiss()
                },
                enabled = canSave
            ) {
                Text(if (isEditing) "Actualizar" else "Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
