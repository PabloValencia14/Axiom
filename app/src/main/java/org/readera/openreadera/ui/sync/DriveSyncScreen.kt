package org.readera.openreadera.ui.sync

import android.app.Activity
import android.content.Intent
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.launch
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.sync.DriveAccountGuard
import org.readera.openreadera.sync.GoogleAuthManager
import org.readera.openreadera.sync.GoogleDriveSyncManager
import org.readera.openreadera.sync.GoogleUserProfile
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DriveSyncScreen(
    books: List<Book> = emptyList(),
    onOpenBook: (Book) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authManager = remember { GoogleAuthManager(context) }
    val syncManager = remember { GoogleDriveSyncManager(context) }

    var accountSelectionSession by remember { mutableStateOf<DriveAccountGuard.Session?>(null) }
    var userProfile by remember { mutableStateOf(authManager.getUserProfile()) }
    var isSyncing by remember { mutableStateOf(false) }
    var lastSyncTime by remember { mutableLongStateOf(syncManager.getLastSyncTime()) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    var autoSync by remember { mutableStateOf(syncManager.isAutoSyncEnabled) }

    // Folder selection state
    var targetFolderName by remember { mutableStateOf(syncManager.getTargetFolderName()) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var customFolderInput by remember { mutableStateOf(targetFolderName) }
    var safFolderName by remember { mutableStateOf(syncManager.getSafFolderName()) }
    var isDriveConnected by remember { mutableStateOf(syncManager.isDriveConnected()) }
    var showCloudInfoDialog by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current


    // Launcher for Storage Access Framework (SAF) Google Drive Folder Connection
    val safFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {}
            val docFile = DocumentFile.fromTreeUri(context, uri)
            val folderName = docFile?.name ?: "Google Drive"
            syncManager.saveSafFolder(uri, folderName)
            safFolderName = folderName
            targetFolderName = folderName
            isDriveConnected = syncManager.isDriveConnected()
            syncMessage = "¡Carpeta de Google Drive vinculada: $folderName!"
        }
    }

    // Launcher for Google Consent / Authorization (UserRecoverableAuthException)
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val email = userProfile?.email
            if (!email.isNullOrBlank()) {
                scope.launch {
                    val tokenRes = authManager.fetchOAuthToken(email)
                    tokenRes.onSuccess { token ->
                        userProfile = authManager.saveAccountByEmail(email, token = token)
                        isDriveConnected = syncManager.isDriveConnected()
                        syncMessage = "¡Permiso de Google Drive concedido para $email!"
                    }.onFailure { err ->
                        syncMessage = "Error al obtener token tras permiso: ${err.localizedMessage}"
                    }
                }
            }
        }
    }

    fun requestDriveAuthorization(
        email: String,
        session: DriveAccountGuard.Session = authManager.beginAccountSelection()
    ) {
        scope.launch {
            val tokenRes = authManager.fetchOAuthToken(email, session)
            tokenRes.onSuccess { token ->
                userProfile = authManager.saveAccountByEmail(email, token = token)
                isDriveConnected = syncManager.isDriveConnected()
                syncMessage = "Conectado y autorizado con Google Drive: $email"
            }.onFailure { err ->
                if (err is com.google.android.gms.auth.UserRecoverableAuthException) {
                    try {
                        err.intent?.let { consentLauncher.launch(it) }
                    } catch (e: Exception) {
                        syncMessage = "Error al abrir ventana de permisos: ${e.localizedMessage}"
                    }
                } else {
                    val msg = err.localizedMessage ?: "Error al solicitar autorización"
                    syncMessage = msg
                    if (msg.contains("Google Cloud Console", ignoreCase = true) || msg.contains("SHA-1", ignoreCase = true)) {
                        showCloudInfoDialog = true
                    }
                }
            }
        }
    }

    // Native Google Account Chooser launcher - captures exact account tapped by user
    val accountChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val launchSession = accountSelectionSession
        accountSelectionSession = null
        if (launchSession == null) return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val chosenEmail = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
            if (!chosenEmail.isNullOrBlank()) {
                val (profile, session) = runCatching {
                    authManager.selectAccountByEmail(chosenEmail, launchSession = launchSession)
                }.getOrNull() ?: return@rememberLauncherForActivityResult
                userProfile = profile
                syncMessage = "Cuenta seleccionada: $chosenEmail. Verificando acceso a Drive..."
                requestDriveAuthorization(chosenEmail, session)
            }
        }
    }

    val googleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val launchSession = accountSelectionSession
        accountSelectionSession = null
        if (launchSession == null) return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                if (account != null && !account.email.isNullOrBlank()) {
                    scope.launch {
                        val res = authManager.handleSignInAccount(account, launchSession)
                        res.onSuccess {
                            userProfile = it
                            syncMessage = "Sesión iniciada con: ${it.email}"
                        }.onFailure { err ->
                            userProfile = authManager.getUserProfile()
                            if (err is com.google.android.gms.auth.UserRecoverableAuthException) {
                                try {
                                    err.intent?.let { consentLauncher.launch(it) }
                                } catch (_: Exception) {}
                            } else {
                                syncMessage = "Sesión iniciada con: ${userProfile?.email}"
                            }
                        }
                    }
                    return@rememberLauncherForActivityResult
                }
            } catch (_: Exception) {}

            val fallbackEmail = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
            if (!fallbackEmail.isNullOrBlank()) {
                val (profile, session) = runCatching {
                    authManager.selectAccountByEmail(fallbackEmail, launchSession = launchSession)
                }.getOrNull() ?: return@rememberLauncherForActivityResult
                userProfile = profile
                requestDriveAuthorization(fallbackEmail, session)
            }
        }
    }

    fun launchGoogleAccountChooser() {
        if (accountSelectionSession != null) return
        accountSelectionSession = authManager.beginAccountSelection()
        try {
            val chooseIntent = android.accounts.AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                null,
                null,
                null,
                null
            )
            accountChooserLauncher.launch(chooseIntent)
        } catch (_: Exception) {
            try {
                googleLauncher.launch(authManager.googleSignInClient.signInIntent)
            } catch (ex: Exception) {
                accountSelectionSession = null
                syncMessage = "Error al abrir selector de cuenta: ${ex.localizedMessage}"
            }
        }
    }

    val rotationAnim = rememberInfiniteTransition()
    val angle by rotationAnim.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    // Folder Selection Dialog
    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Carpeta de destino en Google Drive")
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Selecciona o escribe el nombre de la carpeta de Google Drive donde se subirán y organizarán los libros.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Carpetas sugeridas:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        syncManager.getAvailableFolderPresets().forEach { preset ->
                            FilterChip(
                                selected = customFolderInput.equals(preset, ignoreCase = true),
                                onClick = { customFolderInput = preset },
                                label = { Text(preset) },
                                leadingIcon = if (customFolderInput.equals(preset, ignoreCase = true)) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else {
                                    { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = customFolderInput,
                        onValueChange = { customFolderInput = it },
                        label = { Text("Nombre de la carpeta en Drive") },
                        placeholder = { Text("Ej: OpenReadEra, Mis Libros...") },
                        leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanName = customFolderInput.trim().ifBlank { "OpenReadEra" }
                        syncManager.setTargetFolderName(cleanName)
                        targetFolderName = cleanName
                        showFolderDialog = false
                        syncMessage = "Carpeta de destino establecida en: $cleanName"
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Google Cloud Info & Assistance Dialog
    if (showCloudInfoDialog) {
        AlertDialog(
            onDismissRequest = { showCloudInfoDialog = false },
            icon = {
                Icon(
                    Icons.Default.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text("Autorización de API (Google Cloud)")
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Google Play Services exige que las aplicaciones que solicitan la API directa de Google Drive estén registradas en Google Cloud Console con su huella digital criptográfica:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "Nombre de paquete:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "org.readera.openreadera",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString("org.readera.openreadera"))
                                        syncMessage = "Nombre de paquete copiado al portapapeles"
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", modifier = Modifier.size(16.dp))
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                "Huella digital SHA-1:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "A1:DD:FA:0F:BE:58:C9:66:A8:CD:54:97:29:D6:C9:63:C7:E6:B0:D0",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString("A1:DD:FA:0F:BE:58:C9:66:A8:CD:54:97:29:D6:C9:63:C7:E6:B0:D0"))
                                        syncMessage = "Huella SHA-1 copiada al portapapeles"
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Stars,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Solución directa (Sin configurar Google Cloud):",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Pulsa el botón inferior para vincular una carpeta de Google Drive directamente a través de la app instalada en la tablet. Podrás subir y sincronizar sin necesidad de crear proyectos en la nube.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCloudInfoDialog = false
                        safFolderLauncher.launch(null)
                    }
                ) {
                    Icon(Icons.Default.FolderShared, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Vincular carpeta en Drive")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloudInfoDialog = false }) {
                    Text("Cerrar")
                }
            }
        )
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        // Screen Header
        Text(
            text = "Sincronización con Google Drive",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Sincroniza tus libros, página de lectura, citas y reseñas entre todos tus dispositivos.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        // Status / Feedback Banner
        syncMessage?.let {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { syncMessage = null }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }

        // Responsive layout: 2 columns on tablet/wide landscape, TabRow on phone
        val configuration = LocalConfiguration.current
        val isWideScreen = configuration.screenWidthDp >= 720
        var selectedTab by remember { mutableIntStateOf(0) }

        @Composable
        fun SettingsColumn(modifier: Modifier = Modifier) {
            Column(
                modifier = modifier
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Google Account Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                if (userProfile?.photoUrl != null) {
                                    AsyncImage(
                                        model = userProfile?.photoUrl,
                                        contentDescription = "Avatar",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.CloudSync,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                if (userProfile != null) {
                                    Text(
                                        text = userProfile!!.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = userProfile!!.email,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val isAuthorizedOAuth = userProfile?.accessToken?.startsWith("ya29") == true
                                    val hasSafFolder = safFolderName != null
                                    val isConnectedToDrive = isAuthorizedOAuth || hasSafFolder

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 2.dp)
                                    ) {
                                        Icon(
                                            if (isConnectedToDrive) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                                            contentDescription = null,
                                            tint = if (isConnectedToDrive) Color(0xFF2E7D32) else Color(0xFFE65100),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = when {
                                                hasSafFolder -> "Google Drive conectado ($safFolderName)"
                                                isAuthorizedOAuth -> "Google Drive conectado (API autorizada)"
                                                else -> "Falta vincular carpeta de Drive"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isConnectedToDrive) Color(0xFF2E7D32) else Color(0xFFE65100),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "No has iniciado sesión",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Inicia sesión con Google para sincronizar.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (userProfile != null) {
                            val isAuthorizedOAuth = userProfile?.accessToken?.startsWith("ya29") == true
                            val hasSafFolder = safFolderName != null
                            val isConnectedToDrive = isAuthorizedOAuth || hasSafFolder

                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!isConnectedToDrive) {
                                    Button(
                                        onClick = { safFolderLauncher.launch(null) }
                                    ) {
                                        Icon(Icons.Default.FolderShared, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Vincular Drive")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            showCloudInfoDialog = true
                                            requestDriveAuthorization(userProfile!!.email)
                                        }
                                    ) {
                                        Text("Autorizar API")
                                    }
                                } else {
                                    Button(
                                        onClick = { safFolderLauncher.launch(null) }
                                    ) {
                                        Icon(Icons.Default.FolderShared, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(if (hasSafFolder) "Cambiar carpeta" else "Vincular carpeta")
                                    }
                                    if (hasSafFolder) {
                                        OutlinedButton(
                                            onClick = {
                                                syncManager.disconnectSafFolder()
                                                safFolderName = null
                                                isDriveConnected = syncManager.isDriveConnected()
                                                syncMessage = "Carpeta de Google Drive desvinculada"
                                            }
                                        ) {
                                            Text("Desvincular")
                                        }
                                    }
                                }
                                OutlinedButton(
                                    onClick = { launchGoogleAccountChooser() }
                                ) {
                                    Text("Cambiar")
                                }
                                OutlinedButton(
                                    onClick = {
                                        authManager.signOut()
                                        userProfile = null
                                        safFolderName = null
                                        isDriveConnected = false
                                        syncMessage = "Sesión cerrada"
                                    }
                                ) {
                                    Text("Cerrar")
                                }
                            }
                        } else {
                            Button(
                                onClick = { launchGoogleAccountChooser() }
                            ) {
                                Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Iniciar sesión")
                            }
                        }
                    }
                }

                // 2. FOLDER SELECTION CARD (Storage Access Framework Google Drive Integration)
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (safFolderName != null)
                            Color(0xFF2E7D32).copy(alpha = 0.08f)
                        else
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.FolderShared,
                                    contentDescription = null,
                                    tint = if (safFolderName != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Carpeta de destino en Google Drive",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            FilledTonalButton(
                                onClick = { safFolderLauncher.launch(null) }
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (safFolderName != null) "Cambiar en Drive" else "Vincular carpeta")
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (safFolderName != null)
                                Color(0xFF2E7D32).copy(alpha = 0.12f)
                            else
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (safFolderName != null) Icons.Default.CheckCircle else Icons.Default.CloudQueue,
                                    contentDescription = null,
                                    tint = if (safFolderName != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = if (safFolderName != null) "Google Drive / $safFolderName" else "Google Drive / $targetFolderName",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (safFolderName != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = if (safFolderName != null)
                                            "Sincronización activa directamente con la app de Google Drive"
                                        else
                                            "Pulsa 'Vincular carpeta' para seleccionar tu carpeta en Google Drive",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (safFolderName != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Los libros que subas y el progreso de lectura se sincronizarán directamente en esta carpeta.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 3. Sync State & Manual Trigger Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Estado de sincronización completa", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (lastSyncTime > 0) "Última sincronización: ${dateFormat.format(Date(lastSyncTime))}" else "Aún no se ha sincronizado",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Button(
                                onClick = {
                                    if (!isSyncing) {
                                        if (!syncManager.isDriveConnected()) {
                                            syncMessage = "Vincula primero tu carpeta de Google Drive"
                                            safFolderLauncher.launch(null)
                                            return@Button
                                        }
                                        isSyncing = true
                                        scope.launch {
                                            val result = syncManager.performSync()
                                            isSyncing = false
                                            result.onSuccess {
                                                lastSyncTime = it.lastSyncTimestamp
                                                syncMessage = buildString {
                                                    append("¡Sincronización completada! (${it.booksSynced} libros")
                                                    if (it.restoredBooks > 0) append(", ${it.restoredBooks} restaurados")
                                                    append(", ${it.bookmarksSynced} marcadores y ${it.quotesSynced} citas nuevas)")
                                                }
                                            }.onFailure { err ->
                                                if (err is com.google.android.gms.auth.UserRecoverableAuthException) {
                                                    try {
                                                        err.intent?.let { consentLauncher.launch(it) }
                                                    } catch (_: Exception) {}
                                                } else {
                                                    syncMessage = "Error al sincronizar: ${err.localizedMessage}"
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = !isSyncing
                            ) {
                                Icon(
                                    Icons.Default.Sync,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .then(if (isSyncing) Modifier.rotate(angle) else Modifier)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (isSyncing) "Sincronizando..." else "Sincronizar todo")
                            }
                        }
                    }
                }

                Text("Opciones de sincronización", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "La copia incluye libros y portadas, progreso, marcadores, citas, dibujos, colecciones, historial, estadísticas, preferencias no sensibles, catálogos OPDS y categorías manuales. No incluye cuentas, contraseñas, cookies, claves API ni rutas locales.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sincronización automática", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Mantiene la copia completa al día con conexión a Internet",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = autoSync, onCheckedChange = {
                                autoSync = it
                                syncManager.isAutoSyncEnabled = it
                            })
                        }
                    }
                }
        }
        }
        @Composable
        fun UploadColumn(modifier: Modifier = Modifier) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                modifier = modifier
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(
                        Icons.Default.CloudSync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        "Copia completa de la aplicación",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${books.size} libros en la biblioteca. La sincronización carga y restaura sus archivos y portadas junto con los datos asociados.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Usa «Sincronizar todo» en Ajustes para actualizar la copia. Los archivos se verifican por SHA-256 antes de restaurarlos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }


        if (isWideScreen) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                SettingsColumn(Modifier.weight(1f).fillMaxHeight())
                UploadColumn(Modifier.weight(1.2f).fillMaxHeight())
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Ajustes") },
                        icon = { Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Copia completa") },
                        icon = { Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (selectedTab == 0) {
                    SettingsColumn(Modifier.fillMaxSize())
                } else {
                    UploadColumn(Modifier.fillMaxSize())
                }
            }
        }
    }
}
