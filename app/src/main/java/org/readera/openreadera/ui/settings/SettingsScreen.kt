package org.readera.openreadera.ui.settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.readera.openreadera.data.model.AppThemeMode
import org.readera.openreadera.data.model.ReaderColorTheme
import org.readera.openreadera.data.model.ReaderSettings
import org.readera.openreadera.data.preferences.ReaderPreferences
import org.readera.openreadera.engine.MobiConverter
import org.readera.openreadera.data.security.OpenRouterKeyStore
import org.readera.openreadera.ui.settings.components.OpenRouterSettingsCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferences: ReaderPreferences? = null,
    onBack: () -> Unit,
    onTriggerScan: () -> Unit
) {
    var selectedCategoryTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val openRouterKeyStore = remember(context) { OpenRouterKeyStore(context) }

    // Subscreen dialog states
    var showScanFilesDialog by remember { mutableStateOf(false) }
    var showPageTurningDialog by remember { mutableStateOf(false) }
    var showTtsSettingsDialog by remember { mutableStateOf(false) }
    var showIntegrationsDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showAboutOpenReadEraDialog by remember { mutableStateOf(false) }
    var showAppThemeDialog by remember { mutableStateOf(false) }
    var showReaderThemeDialog by remember { mutableStateOf(false) }

    // Preferences states
    val appThemeMode by preferences?.appThemeMode?.collectAsState() ?: remember { mutableStateOf(AppThemeMode.SYSTEM) }
    val dynamicColor by preferences?.dynamicColor?.collectAsState() ?: remember { mutableStateOf(false) }
    val libraryCompactMode by preferences?.libraryCompactMode?.collectAsState() ?: remember { mutableStateOf(false) }
    val readerSettings by preferences?.settings?.collectAsState() ?: remember { mutableStateOf(ReaderSettings()) }
    val ttsSpeechRate by preferences?.ttsSpeechRate?.collectAsState() ?: remember { mutableStateOf(1.0f) }
    val useNeuralTtsOnline by preferences?.useNeuralTtsOnline?.collectAsState() ?: remember { mutableStateOf(false) }

    // Toggle states
    var resumeBookOnStart by remember { mutableStateOf(false) }
    var fullScreenMode by remember { mutableStateOf(true) }
    var dragBrightness by remember { mutableStateOf(false) }
    var dayNightTouch by remember { mutableStateOf(true) }
    var bookmarkTouch by remember { mutableStateOf(true) }
    var multiDocumentMode by remember { mutableStateOf(false) }
    var screenTimeoutExtend by remember { mutableStateOf(true) }
    var usageStats by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Category Tabs: General, Lectura, Otros
            TabRow(selectedTabIndex = selectedCategoryTab) {
                Tab(
                    selected = selectedCategoryTab == 0,
                    onClick = { selectedCategoryTab = 0 },
                    text = {
                        Text(
                            "General",
                            fontWeight = if (selectedCategoryTab == 0) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                )
                Tab(
                    selected = selectedCategoryTab == 1,
                    onClick = { selectedCategoryTab = 1 },
                    text = {
                        Text(
                            "Lectura",
                            fontWeight = if (selectedCategoryTab == 1) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                )
                Tab(
                    selected = selectedCategoryTab == 2,
                    onClick = { selectedCategoryTab = 2 },
                    text = {
                        Text(
                            "Otros",
                            fontWeight = if (selectedCategoryTab == 2) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
            ) {
                when (selectedCategoryTab) {
                    // TAB 0: General
                    0 -> {
                        SettingsItem(
                            title = "Tema de la interfaz",
                            subtitle = appThemeMode.title,
                            icon = Icons.Default.Palette,
                            onClick = { showAppThemeDialog = true }
                        )
                        SettingsSwitchItem(
                            title = "Color dinámico (Material You)",
                            subtitle = "Usa la paleta de colores del fondo de pantalla",
                            checked = dynamicColor,
                            onCheckedChange = { preferences?.updateDynamicColor(it) }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            title = "Examinar archivos",
                            subtitle = "Configura escaneo y fuentes de biblioteca",
                            icon = Icons.Default.FolderOpen,
                            onClick = { showScanFilesDialog = true }
                        )
                        SettingsSwitchItem(
                            title = "Lista compacta en biblioteca",
                            subtitle = "Muestra portadas pequeñas y densidad optimizada",
                            checked = libraryCompactMode,
                            onCheckedChange = { preferences?.updateLibraryCompactMode(it) }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsSwitchItem(
                            title = "Leer el libro al comenzar",
                            subtitle = "Continúa el último libro al iniciar la app",
                            checked = resumeBookOnStart,
                            onCheckedChange = { resumeBookOnStart = it }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            title = "Lenguaje de interfaz",
                            subtitle = "Español (predeterminado)",
                            icon = Icons.Default.Language,
                            onClick = { showLanguageDialog = true }
                        )
                    }

                    // TAB 1: Lectura
                    1 -> {
                        SettingsItem(
                            title = "Tema de lectura predeterminado",
                            subtitle = when (readerSettings.theme) {
                                ReaderColorTheme.SYSTEM -> "Sistema (Sincronizado)"
                                ReaderColorTheme.DAY -> "Día (Claro)"
                                ReaderColorTheme.SEPIA -> "Sepia"
                                ReaderColorTheme.NIGHT -> "Noche (Oscuro)"
                                ReaderColorTheme.OLED -> "Negro Puro (OLED)"
                            },
                            icon = Icons.Default.ColorLens,
                            onClick = { showReaderThemeDialog = true }
                        )
                        SettingsItem(
                            title = "Pasaje de hojas e Indicador de páginas",
                            subtitle = "Paginación global, animaciones y esquemas táctiles",
                            icon = Icons.Default.AutoStories,
                            onClick = { showPageTurningDialog = true }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            title = "Texto a voz (TTS)",
                            subtitle = "Velocidad personalizada: ${String.format(java.util.Locale.ROOT, "%.2f", ttsSpeechRate)}x",
                            icon = Icons.Default.VolumeUp,
                            onClick = { showTtsSettingsDialog = true }
                        )
                        SettingsSwitchItem(
                            title = "Voz neural en línea (Google)",
                            subtitle = "Activar envía el texto leído a Google para sintetizar voz; desactivar usa la voz local de Android y no envía texto.",
                            checked = useNeuralTtsOnline,
                            onCheckedChange = { preferences?.updateUseNeuralTtsOnline(it) }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            title = "Traductor, Diccionario, Búsqueda Web",
                            subtitle = "Integraciones con apps externas de consulta",
                            icon = Icons.Default.Translate,
                            onClick = { showIntegrationsDialog = true }
                        )
                        SettingsSwitchItem(
                            title = "Modo pantalla completa",
                            subtitle = "Oculta barras de estado y navegación del sistema",
                            checked = fullScreenMode,
                            onCheckedChange = { fullScreenMode = it }
                        )
                        SettingsSwitchItem(
                            title = "Brillo por arrastre",
                            subtitle = "Ajusta el brillo deslizando en el borde izquierdo",
                            checked = dragBrightness,
                            onCheckedChange = { dragBrightness = it }
                        )
                        SettingsSwitchItem(
                            title = "Día-Noche táctil",
                            subtitle = "Cambia el tema de color tocando la esquina superior izquierda",
                            checked = dayNightTouch,
                            onCheckedChange = { dayNightTouch = it }
                        )
                        SettingsSwitchItem(
                            title = "Señalador táctil",
                            subtitle = "Guarda marcador tocando la esquina superior derecha",
                            checked = bookmarkTouch,
                            onCheckedChange = { bookmarkTouch = it }
                        )
                        SettingsSwitchItem(
                            title = "Apagado de pantalla",
                            subtitle = "Mantiene la pantalla encendida 10 min más que el sistema",
                            checked = screenTimeoutExtend,
                            onCheckedChange = { screenTimeoutExtend = it }
                        )
                    }

                    // TAB 2: Otros
                    2 -> {
                        SettingsSwitchItem(
                            title = "Modo de documentos múltiples",
                            subtitle = "Abre cada documento en ventana Android separada",
                            checked = multiDocumentMode,
                            onCheckedChange = { multiDocumentMode = it }
                        )
                        SettingsSwitchItem(
                            title = "Estadísticas de uso",
                            subtitle = "Envío anónimo de estadísticas para mejorar la app",
                            checked = usageStats,
                            onCheckedChange = { usageStats = it }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            title = "Acerca de Axiom",
                            subtitle = "Versión 1.0.0 (Open Source), novedades y licencias",
                            icon = Icons.Default.Info,
                            onClick = { showAboutOpenReadEraDialog = true }
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Conversor MOBI sin DRM", fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                val isAvailable = MobiConverter.isAvailable
                                Surface(
                                    shape = CircleShape,
                                    color = if (isAvailable) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    contentColor = if (isAvailable) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (isAvailable) Icons.Default.CheckCircle else Icons.Default.Info,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(if (isAvailable) "Disponible" else "No disponible")
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "MOBI, AZW y AZW3 sin DRM. Los documentos protegidos no se descifran.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .semantics(mergeDescendants = true) { heading() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "IA e integraciones",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Funciones opcionales de inteligencia artificial para lectura y análisis de PDF. Tus documentos permanecen en el dispositivo y solo se envían fragmentos cuando confirmas explícitamente una consulta.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OpenRouterSettingsCard(
                    keyStore = openRouterKeyStore,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Subscreens Dialogs
    if (showScanFilesDialog) {
        ScanFilesDialog(
            onTriggerScan = onTriggerScan,
            onDismiss = { showScanFilesDialog = false }
        )
    }

    if (showPageTurningDialog) {
        PageTurningDialog(onDismiss = { showPageTurningDialog = false })
    }

    if (showTtsSettingsDialog) {
        TtsSettingsDialog(
            speechRate = ttsSpeechRate,
            onSpeechRateChange = { preferences?.updateTtsSpeechRate(it) },
            onDismiss = { showTtsSettingsDialog = false }
        )
    }

    if (showIntegrationsDialog) {
        IntegrationsDialog(onDismiss = { showIntegrationsDialog = false })
    }

    if (showLanguageDialog) {
        LanguageDialog(onDismiss = { showLanguageDialog = false })
    }

    if (showAboutOpenReadEraDialog) {
        AboutOpenReadEraDialog(onDismiss = { showAboutOpenReadEraDialog = false })
    }

    if (showAppThemeDialog) {
        AppThemeDialog(
            currentMode = appThemeMode,
            onSelectMode = {
                preferences?.updateAppThemeMode(it)
                showAppThemeDialog = false
            },
            onDismiss = { showAppThemeDialog = false }
        )
    }

    if (showReaderThemeDialog) {
        ReaderThemeDialog(
            currentTheme = readerSettings.theme,
            onSelectTheme = {
                preferences?.updateTheme(it)
                showReaderThemeDialog = false
            },
            onDismiss = { showReaderThemeDialog = false }
        )
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// Subdialogs
@Composable
private fun ScanFilesDialog(
    onTriggerScan: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Examinar archivos", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Búsqueda de libros y documentos compatibles en el almacenamiento del dispositivo y en Descargas.")
                Spacer(modifier = Modifier.height(6.dp))
                Text("Formatos admitidos:", fontWeight = FontWeight.SemiBold)
                Text("• EPUB, PDF, MOBI, FB2, CBZ, CBR, TXT, RTF, DOC, DOCX, DJVU")
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        onTriggerScan()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("COMENZAR BÚSQUEDA")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("CERRAR") }
        }
    )
}

@Composable
private fun PageTurningDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pasaje de hojas e indicador", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Animación del pasaje de páginas: Deslizamiento suave")
                Text("Botones de volumen: Desactivado")
                Spacer(modifier = Modifier.height(6.dp))
                Text("Pasaje horizontal: Esquema 1 (zonas táctiles laterales)", fontWeight = FontWeight.SemiBold)
                Text("Indicador de páginas: Barra con posición actual")
                Spacer(modifier = Modifier.height(6.dp))
                Text("Pasaje vertical: Desplazamiento continuo", fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("ACEPTAR") }
        }
    )
}

@Composable
private fun TtsSettingsDialog(
    speechRate: Float,
    onSpeechRateChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Texto a voz (TTS)", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Velocidad del habla",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${String.format(java.util.Locale.ROOT, "%.2f", speechRate)}x",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Slider(
                        value = speechRate.coerceIn(0.5f, 2.0f),
                        onValueChange = { newRate ->
                            val rounded = (Math.round(newRate * 20f) / 20f).coerceIn(0.5f, 2.0f)
                            onSpeechRateChange(rounded)
                        },
                        valueRange = 0.5f..2.0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("ACEPTAR") }
        }
    )
}

@Composable
private fun IntegrationsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Traductor, Diccionario y Web", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Traductor: Google Traductor (externo)")
                Text("Diccionario: ColorDict / GoldenDict (externo)")
                Text("Búsqueda Web: Navegador predeterminado")
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("ACEPTAR") }
        }
    )
}

@Composable
private fun LanguageDialog(onDismiss: () -> Unit) {
    val languages = listOf("Automáticamente", "Español", "English", "Català", "Galego", "Euskara", "Français", "Deutsch", "Italiano", "Português", "Русский", "中文")
    var selectedLang by remember { mutableStateOf("Español") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lenguaje de interfaz", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                items(languages) { lang ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedLang = lang }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selectedLang == lang, onClick = { selectedLang = lang })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(lang)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("APLICAR") }
        }
    )
}

@Composable
private fun AboutOpenReadEraDialog(onDismiss: () -> Unit) {
    var tabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("LO NUEVO", "ACERCA DE", "LICENCIAS")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Axiom", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                TabRow(selectedTabIndex = tabIndex) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = tabIndex == index,
                            onClick = { tabIndex = index },
                            text = { Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                when (tabIndex) {
                    0 -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Text("Versión 1.0.0 (Open Source)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("• Sincronización automática periódica en segundo plano con Google Drive para libros y progreso.\n• Modos de lectura Día, Noche, Sepia y Negro Puro OLED de alto contraste.\n• Soporte multiformato: EPUB, PDF, MOBI, FB2, CBZ, CBR, TXT.\n• Descargador integrado con catálogos Z-Library, arXiv y MangaDex.\n• Interfaz Material 3 optimizada para tablets y teléfonos.")
                            }
                        }
                    }
                    1 -> {
                        Column {
                            Text("Axiom para Android", fontWeight = FontWeight.Bold)
                            Text("Lector de libros y documentos libre y de código abierto.")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Desarrollado con Jetpack Compose Material 3, Kotlin Coroutines, WorkManager y Room Database.")
                        }
                    }
                    2 -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Text("Licencias de código abierto:", fontWeight = FontWeight.Bold)
                                Text("• MuPDF: GNU AGPL\n• CoolReader: GNU GPL v2\n• Libmobi: GNU LGPL\n• DjVuLibre: GNU GPL v2\n• FreeType: FTL / GPL\n• Jetpack Compose: Apache 2.0")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("CERRAR") }
        }
    )
}

@Composable
private fun AppThemeDialog(
    currentMode: AppThemeMode,
    onSelectMode: (AppThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tema de la interfaz", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                AppThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectMode(mode) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (mode == currentMode),
                            onClick = { onSelectMode(mode) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = mode.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CERRAR")
            }
        }
    )
}

@Composable
private fun ReaderThemeDialog(
    currentTheme: ReaderColorTheme,
    onSelectTheme: (ReaderColorTheme) -> Unit,
    onDismiss: () -> Unit
) {
    val themeOptions = listOf(
        ReaderColorTheme.SYSTEM to ("Sistema (Sincronizado)" to "Sigue automáticamente el tema claro u oscuro del dispositivo"),
        ReaderColorTheme.DAY to ("Día" to "Fondo marfil claro para lectura diurna"),
        ReaderColorTheme.SEPIA to ("Sepia" to "Tono cálido apergaminado suave"),
        ReaderColorTheme.NIGHT to ("Noche" to "Fondo carbón oscuro descansado"),
        ReaderColorTheme.OLED to ("Negro Puro (OLED)" to "Fondo negro absoluto (0% emisión) para pantallas AMOLED")
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tema de lectura predeterminado", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                themeOptions.forEach { (theme, pair) ->
                    val (title, desc) = pair
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectTheme(theme) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (theme == currentTheme),
                            onClick = { onSelectTheme(theme) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CERRAR")
            }
        }
    )
}

