package org.readera.openreadera

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.readera.openreadera.data.importer.DocumentUriImporter
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.BookStatus
import org.readera.openreadera.engine.DocumentFormat
import org.readera.openreadera.ui.navigation.AppNavHost
import org.readera.openreadera.ui.theme.OpenReadEraTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val openBookEvents = Channel<Long>(Channel.BUFFERED)
    private val openBookEventFlow = openBookEvents.receiveAsFlow()
    private val windowedMode = MutableStateFlow(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.i(TAG, "Storage permissions result: $permissions")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        windowedMode.value = isInMultiWindowMode

        val initialUri = getIncomingUri(intent)
        val app = applicationContext as OpenReadEraApplication
        setContent {
            val appThemeMode by app.preferences.appThemeMode.collectAsState()
            val dynamicColor by app.preferences.dynamicColor.collectAsState()

            OpenReadEraTheme(
                appThemeMode = appThemeMode,
                dynamicColor = dynamicColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost(
                        openBookEvents = openBookEventFlow,
                        onOpenFile = ::handleIncomingUri,
                        windowedMode = windowedMode
                    )
                }
            }
        }

        checkAndRequestStoragePermissions(skipPrompt = initialUri?.scheme.equals("content", ignoreCase = true))
        initialUri?.let(::handleIncomingUri)
    }

    override fun onResume() {
        super.onResume()
        if (hasStoragePermission()) {
            val app = applicationContext as OpenReadEraApplication
            lifecycleScope.launch {
                app.storageScanner.scanStorage()
            }
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        windowedMode.value = isInMultiWindowMode
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        org.readera.openreadera.ui.reader.ReaderEventBus.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    private fun checkAndRequestStoragePermissions(skipPrompt: Boolean = false) {
        if (skipPrompt && !hasStoragePermission()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else if (!hasStoragePermission()) {
            requestPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        getIncomingUri(intent)?.let(::handleIncomingUri)
    }

    private fun getIncomingUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> getSharedUri(intent)
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun getSharedUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun handleIncomingUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val app = applicationContext as OpenReadEraApplication
                val file = DocumentUriImporter.import(this@MainActivity, uri)
                if (file == null) {
                    Log.e(TAG, "Cannot import document URI: $uri")
                    return@launch
                }
                val book = Book(
                    title = file.nameWithoutExtension.replace('_', ' '),
                    filePath = file.absolutePath,
                    format = DocumentFormat.fromPath(file.absolutePath).displayName,
                    fileSize = file.length(),
                    status = BookStatus.READING,
                    lastOpened = System.currentTimeMillis()
                )
                val existing = app.repository.getBookByPath(book.filePath)
                val bookId = existing?.id ?: app.repository.insertBook(book)
                val storedBook = app.repository.getBookByIdSync(bookId) ?: book.copy(id = bookId)
                app.storageScanner.classifyBook(storedBook)
                Log.i(TAG, "Opened document from URI: $bookId (${book.title})")
                openBookEvents.send(bookId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open document URI: $uri", e)
            }
        }
    }
}
