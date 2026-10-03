package org.readera.openreadera.ui.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readera.openreadera.data.security.OpenRouterKeyStore

private const val MAX_API_KEY_LENGTH = 512

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OpenRouterSettingsCard(
    keyStore: OpenRouterKeyStore,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    var apiKeyInput by remember { mutableStateOf("") }
    var isKeyVisible by remember { mutableStateOf(false) }
    var hasSavedKey by remember { mutableStateOf(false) }
    var isCheckingKey by remember { mutableStateOf(true) }
    var isBusy by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var statusFeedback by remember { mutableStateOf<String?>(null) }
    var isFeedbackError by remember { mutableStateOf(false) }

    LaunchedEffect(keyStore) {
        isCheckingKey = true
        hasSavedKey = try {
            withContext(Dispatchers.IO) {
                keyStore.hasKey()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        isCheckingKey = false
    }

    fun validateInput(rawInput: String): String? {
        val trimmed = rawInput.trim()
        return when {
            trimmed.isEmpty() -> "Introduce una clave API de OpenRouter antes de guardar."
            trimmed.length > MAX_API_KEY_LENGTH -> "La clave API supera el límite de $MAX_API_KEY_LENGTH caracteres."
            trimmed.any(Char::isISOControl) -> "La clave API contiene caracteres de control no permitidos."
            else -> null
        }
    }

    fun saveOrUpdateKey() {
        if (isBusy) return
        val error = validateInput(apiKeyInput)
        if (error != null) {
            validationError = error
            statusFeedback = null
            isFeedbackError = false
            return
        }

        val candidateKey = apiKeyInput.trim()
        val wasConfigured = hasSavedKey
        validationError = null
        isBusy = true

        scope.launch {
            val savedResult = try {
                withContext(Dispatchers.IO) {
                    keyStore.save(candidateKey)
                    keyStore.hasKey()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }

            if (savedResult != null) {
                apiKeyInput = ""
                isKeyVisible = false
                hasSavedKey = savedResult
                isFeedbackError = false
                statusFeedback = if (wasConfigured) {
                    "Clave API actualizada y cifrada en este dispositivo."
                } else {
                    "Clave API guardada y cifrada en este dispositivo."
                }
            } else {
                isFeedbackError = true
                statusFeedback = "No se pudo guardar la clave API. Verifica el formato e inténtalo de nuevo."
            }
            isBusy = false
        }
    }

    fun clearSavedKey() {
        if (isBusy) return
        validationError = null
        isBusy = true

        scope.launch {
            val clearedResult = try {
                withContext(Dispatchers.IO) {
                    keyStore.clear()
                    keyStore.hasKey()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }

            apiKeyInput = ""
            isKeyVisible = false
            if (clearedResult != null) {
                hasSavedKey = clearedResult
                isFeedbackError = false
                statusFeedback = "Clave API eliminada de este dispositivo."
            } else {
                isFeedbackError = true
                statusFeedback = "No se pudo eliminar la clave API almacenada."
            }
            isBusy = false
        }
    }

    Card(
        modifier = modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Asistente IA (OpenRouter)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Consultas, resúmenes y traducción de PDF",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                val badgeText = when {
                    isCheckingKey -> "Verificando…"
                    hasSavedKey -> "Clave configurada"
                    else -> "Sin clave"
                }
                Surface(
                    shape = CircleShape,
                    color = if (hasSavedKey) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (hasSavedKey) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Estado de la clave OpenRouter: $badgeText"
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (hasSavedKey) Icons.Default.CheckCircle else Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "La clave se cifra con Android Keystore en este dispositivo y nunca se muestra tras guardarla. El texto del PDF solo se envía a OpenRouter cuando confirmas una acción de IA.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { newValue ->
                    apiKeyInput = newValue
                    if (validationError != null) {
                        validationError = null
                    }
                },
                label = {
                    Text(
                        text = if (hasSavedKey) {
                            "Nueva clave API de OpenRouter"
                        } else {
                            "Clave API de OpenRouter"
                        }
                    )
                },
                placeholder = {
                    Text(
                        text = if (hasSavedKey) {
                            "Introduce una nueva clave para reemplazar la actual"
                        } else {
                            "sk-or-v1-..."
                        }
                    )
                },
                visualTransformation = if (isKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(
                        onClick = { isKeyVisible = !isKeyVisible },
                        enabled = !isBusy && apiKeyInput.isNotEmpty(),
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(
                            imageVector = if (isKeyVisible) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                            contentDescription = if (isKeyVisible) {
                                "Ocultar clave API"
                            } else {
                                "Mostrar clave API"
                            }
                        )
                    }
                },
                isError = validationError != null,
                supportingText = {
                    val currentError = validationError
                    if (currentError != null) {
                        Text(
                            text = currentError,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            text = if (hasSavedKey) {
                                "Por seguridad, la clave guardada permanece oculta. Escribe otra para actualizarla."
                            } else {
                                "Pega tu clave personal de OpenRouter."
                            }
                        )
                    }
                },
                singleLine = true,
                enabled = !isBusy,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { saveOrUpdateKey() }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { saveOrUpdateKey() },
                    enabled = !isBusy && !isCheckingKey,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (hasSavedKey) "Actualizar clave" else "Guardar clave",
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (hasSavedKey) {
                    OutlinedButton(
                        onClick = { clearSavedKey() },
                        enabled = !isBusy && !isCheckingKey,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Eliminar clave",
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            val feedbackMessage = statusFeedback
            if (feedbackMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isFeedbackError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (isFeedbackError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isFeedbackError) {
                                Icons.Default.ErrorOutline
                            } else {
                                Icons.Default.CheckCircle
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = feedbackMessage,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
